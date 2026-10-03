#!/usr/bin/env python3
"""Helper for The Agent workflow (.github/workflows/the-agent.yml).

Subcommands:
  check-issue ISSUE_JSON          fail unless the issue is an open issue (not a PR)
  tier TIER                       print tier settings as key=value lines for $GITHUB_OUTPUT
  prompt ISSUE_JSON TIER          print the engine prompt built from .agent/prompt.md
  engine-summary                  print the engine's result, error and blocked tool calls, and
                                  raise them as annotations (paths come from env vars)
  report                          decide the outcome and write the PR body, issue comment,
                                  commit message and run record (paths come from env vars)

Standard library only, so it runs on any GitHub runner without installing anything.
"""

import json
import os
import re
import sys
from pathlib import Path

AGENT_DIR = Path(__file__).resolve().parent
RUNNER_VERSION = "v0.1"

# Tier -> engine settings and Superpowers process depth.
TIERS = {
    "fast": {
        "model": "haiku",
        "max_turns": 40,
        "skills": ["test-driven-development", "verification-before-completion"],
        "plan": False,
    },
    "standard": {
        "model": "sonnet",
        "max_turns": 80,
        "skills": ["test-driven-development", "systematic-debugging", "verification-before-completion"],
        "plan": False,
    },
    "deep": {
        "model": "opus",
        "max_turns": 120,
        "skills": ["test-driven-development", "systematic-debugging", "verification-before-completion"],
        "plan": True,
    },
}

PROTECTED_PREFIXES = (".github/", ".agent/", ".claude/")
WARN_FILES = ("pom.xml",)
VALID_STATUSES = ("FIXED", "NEEDS_INFO", "GAVE_UP")


def fail(message):
    print(f"::error::{message}", file=sys.stderr)
    sys.exit(1)


def load_json(path, default=None):
    try:
        return json.loads(Path(path).read_text())
    except (FileNotFoundError, json.JSONDecodeError):
        return default


def read_text(path, default=""):
    try:
        return Path(path).read_text()
    except FileNotFoundError:
        return default


# --- check-issue -------------------------------------------------------------

def cmd_check_issue(issue_path):
    issue = load_json(issue_path)
    if not issue:
        fail(f"Could not read the issue from {issue_path}.")
    if "pull_request" in issue:
        fail(f"#{issue['number']} is a pull request, not an issue.")
    if issue.get("state") != "open":
        fail(f"Issue #{issue['number']} is {issue.get('state')}; The Agent only works on open issues.")
    print(f"Issue #{issue['number']}: {issue['title']}")


# --- tier ------------------------------------------------------------------

def get_tier(name):
    if name not in TIERS:
        fail(f"Unknown tier '{name}'. Use one of: {', '.join(TIERS)}.")
    return TIERS[name]


def cmd_tier(name):
    tier = get_tier(name)
    print(f"model={tier['model']}")
    print(f"max_turns={tier['max_turns']}")
    print(f"skills={','.join(tier['skills'])}")


# --- prompt ----------------------------------------------------------------

def build_prompt(issue, tier_name):
    tier = get_tier(tier_name)
    template = (AGENT_DIR / "prompt.md").read_text()
    if tier["plan"]:
        plan_step = ("Before writing code, write a short plan: the likely root cause, the files you expect "
                     "to change, the tests you will add, and the risks. Put it in the report's Plan section.")
        plan_section = "   - `## Plan`: the plan you wrote before coding, and anything that changed along the way\n"
    else:
        plan_step = "Form a hypothesis about the root cause before changing anything."
        plan_section = ""
    values = {
        "ISSUE_NUMBER": str(issue["number"]),
        "ISSUE_TITLE": issue["title"],
        "SKILLS": ", ".join(f"`{s}`" for s in tier["skills"]),
        "TIER": tier_name,
        "MAX_TURNS": str(tier["max_turns"]),
        "PLAN_STEP": plan_step,
        "PLAN_SECTION": plan_section,
    }
    out = template
    for key, value in values.items():
        out = out.replace("{{" + key + "}}", value)
    # Insert the untrusted issue body last, so text inside it can't fill other placeholders.
    body = (issue.get("body") or "").strip() or "(The issue has no description.)"
    return out.replace("{{ISSUE_BODY}}", body)


def cmd_prompt(issue_path, tier_name):
    issue = load_json(issue_path)
    if not issue:
        fail(f"Could not read the issue from {issue_path}.")
    sys.stdout.write(build_prompt(issue, tier_name))


# --- report ----------------------------------------------------------------

def parse_numstat(text):
    files, additions, deletions = [], 0, 0
    for line in text.splitlines():
        parts = line.split("\t")
        if len(parts) != 3:
            continue
        add, delete, path = parts
        files.append(path)
        additions += int(add) if add.isdigit() else 0
        deletions += int(delete) if delete.isdigit() else 0
    return files, additions, deletions


def parse_test_log(text):
    """Read the aggregate Surefire line, e.g. 'Tests run: 24, Failures: 0, Errors: 0, Skipped: 0'."""
    matches = re.findall(r"Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)\b(?!,\s*Time)", text)
    if not matches:
        return None
    run, failures, errors, skipped = (int(x) for x in matches[-1])
    return {"run": run, "failures": failures, "errors": errors, "skipped": skipped}


def failure_excerpt(text, limit=40):
    keep = [l for l in text.splitlines()
            if re.search(r"\[ERROR\]|FAIL|Tests run:.*Failures: [1-9]|expected|but was", l)]
    return "\n".join(keep[:limit])


def format_duration(ms):
    if not ms:
        return "n/a"
    seconds = int(ms / 1000)
    return f"{seconds // 60}m {seconds % 60:02d}s"


def engine_error_message(result):
    """Claude Code reports in-run failures (bad credentials, API errors) in the result JSON, not on stderr."""
    if not result:
        return "The engine produced no result (it may have crashed or been stopped before finishing)."
    if not result.get("is_error"):
        return None
    message = str(result.get("result") or "").strip()
    detail = result.get("api_error") or result.get("terminal_reason") or result.get("subtype")
    if message and detail:
        return f"{message} ({detail})"
    return message or str(detail or "Unknown engine error.")


def denied_commands(result):
    """Tool calls the permission allow-list blocked, as short readable strings."""
    denied = []
    for item in (result or {}).get("permission_denials") or []:
        tool = item.get("tool_name", "?")
        tool_input = item.get("tool_input") or {}
        detail = tool_input.get("command") or tool_input.get("file_path") or tool_input.get("path") or ""
        denied.append(f"{tool}: {detail}" if detail else tool)
    return denied


def cmd_engine_summary():
    """Print what the engine did, and raise annotations the run page and API can show."""
    out = Path(os.environ["OUT"])
    result = load_json(out / "engine-result.json", {}) or {}
    error = engine_error_message(result)
    denied = denied_commands(result)

    lines = ["### Engine result", ""]
    if error:
        print(f"::error title=Engine error::{error}")
        lines.append(f"**Error:** {error}")
    else:
        lines.append(f"Finished in {format_duration(result.get('duration_ms'))} and "
                     f"{result.get('num_turns', '?')} turns.")
    if denied:
        print(f"::warning title=Blocked tool calls::{len(denied)} blocked: " + "; ".join(denied)[:900])
        lines += ["", f"**Blocked by the tool allow-list ({len(denied)}):**", ""]
        lines += [f"- `{d}`" for d in denied]
    text = "\n".join(lines) + "\n"
    print(text)
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_path:
        with open(summary_path, "a") as fh:
            fh.write(text + "\n")


def decide(engine_ok, status, files, blocked, tests_passed):
    if not engine_ok:
        return "engine_error"
    if blocked:
        return "blocked"
    if status == "NEEDS_INFO":
        return "needs_info"
    if not files:
        return "no_change"
    return "pr_ready" if tests_passed else "pr_draft"


def cmd_report():
    env = os.environ
    out = Path(env["OUT"])
    issue = load_json(out / "issue.json", {})
    tier_name = env["TIER"]
    tier = get_tier(tier_name)
    engine = env.get("ENGINE", "claude")
    run_url = env.get("RUN_URL", "")

    result = load_json(out / "engine-result.json", {}) or {}
    engine_ok = bool(result) and not result.get("is_error", False)

    status = read_text(out / "status").strip().upper() or "MISSING"
    if status not in VALID_STATUSES:
        status = "MISSING"
    report_md = read_text(out / "report.md").strip()

    files, additions, deletions = parse_numstat(read_text(out / "numstat.txt"))
    blocked_files = [f for f in files if f.startswith(PROTECTED_PREFIXES)]
    warn_files = [f for f in files if f in WARN_FILES]

    verify_log = read_text(out / "verify.log")
    tests = parse_test_log(verify_log)
    tests_passed = env.get("TESTS_PASSED") == "true"

    outcome = decide(engine_ok, status, files, bool(blocked_files), tests_passed)
    engine_error = engine_error_message(result)
    denied = denied_commands(result)

    if tests:
        test_line = (f"{'Passed' if tests_passed else 'FAILED'}: {tests['run']} tests, "
                     f"{tests['failures']} failures, {tests['errors']} errors")
    else:
        test_line = "Passed" if tests_passed else "FAILED (no test summary found)"
    cost = result.get("total_cost_usd")
    cost_line = f"${cost:.2f} (estimate)" if isinstance(cost, (int, float)) else "n/a"
    turns = result.get("num_turns", "n/a")
    duration = format_duration(result.get("duration_ms"))
    skills = ", ".join(tier["skills"])

    facts = [
        ("Engine", f"{engine} (`{tier['model']}`)"),
        ("Tier", f"{tier_name}: {skills}"),
        ("Agent's status", status),
        ("Independent test run", test_line),
        ("Changes", f"{len(files)} files, +{additions} / -{deletions}"),
        ("Engine time", f"{duration}, {turns} turns"),
        ("Cost", cost_line),
    ]
    table = "| | |\n|---|---|\n" + "\n".join(f"| {k} | {v} |" for k, v in facts)

    number = issue.get("number", env.get("ISSUE", "?"))
    title = issue.get("title", "")

    # Pull request body
    lines = []
    if outcome == "pr_draft":
        lines += ["> [!WARNING]",
                  "> **Draft: the independent test run failed.** The agent's change doesn't pass the full suite. "
                  "See the failures at the bottom.", ""]
    if warn_files:
        lines += ["> [!NOTE]", f"> This change modifies {', '.join(f'`{f}`' for f in warn_files)}. "
                  "Check the agent's reasoning under Decisions.", ""]
    if outcome == "pr_ready" and status != "FIXED":
        reported = "didn't report a status" if status == "MISSING" else f"reported `{status}`"
        lines += ["> [!NOTE]",
                  f"> **The agent {reported}, but the workflow's own full test run passed**, so this pull request "
                  "is marked ready. Check the agent's report for anything it couldn't verify.", ""]
    lines += [f"Fixes #{number}", "",
              f"Opened by **The Agent**, an unattended coding agent. [Run log]({run_url})", "",
              table, "",
              "## Agent's report", "",
              report_md or "_The agent didn't write a report._", ""]
    if outcome == "pr_draft":
        excerpt = failure_excerpt(verify_log)
        if excerpt:
            lines += ["## Test failures", "", "```", excerpt, "```", ""]
    if denied:
        lines += ["## Blocked tool calls", "",
                  "The engine tried these, and the tool allow-list blocked them:", ""]
        lines += [f"- `{d}`" for d in denied] + [""]
    lines += ["---", f"<sub>Generated by The Agent runner {RUNNER_VERSION}. "
              "A human must review and approve before merging.</sub>"]
    (out / "pr-body.md").write_text("\n".join(lines) + "\n")

    # Issue comment, for outcomes that don't open a PR
    if outcome == "needs_info":
        comment = [f"**The Agent needs more information before it can work on this.** [Run log]({run_url})",
                   "", report_md or "_No details were provided._"]
    elif outcome == "no_change":
        comment = [f"**The Agent couldn't fix this issue** (status: {status}). No code was changed. "
                   f"[Run log]({run_url})", "", report_md or "_The agent didn't write a report._"]
    elif outcome == "blocked":
        comment = [f"**The Agent's run was stopped.** It modified protected files: "
                   f"{', '.join(f'`{f}`' for f in blocked_files)}. No pull request was opened. [Run log]({run_url})"]
    elif outcome == "engine_error":
        comment = [f"**The Agent's run failed** before producing a result. [Run log]({run_url})", "",
                   f"Error: {engine_error}"]
    else:
        comment = []
    (out / "comment.md").write_text("\n".join(comment) + "\n")

    # Commit message
    commit = [f"Fix #{number}: {title}", "",
              f"Engine: {engine} ({tier_name} tier, model {tier['model']})",
              f"Run: {run_url}"]
    (out / "commit-msg.txt").write_text("\n".join(commit) + "\n")

    # Run record (the data for the comparison table)
    record = {
        "runner_version": RUNNER_VERSION,
        "run_id": env.get("GITHUB_RUN_ID"),
        "run_url": run_url,
        "issue": number,
        "issue_title": title,
        "engine": engine,
        "tier": tier_name,
        "model": tier["model"],
        "skills": tier["skills"],
        "outcome": outcome,
        "agent_status": status,
        "tests_passed": tests_passed,
        "tests": tests,
        "files_changed": files,
        "additions": additions,
        "deletions": deletions,
        "blocked_files": blocked_files,
        "engine_duration_ms": result.get("duration_ms"),
        "engine_turns": result.get("num_turns"),
        "cost_usd_estimate": cost,
        "engine_error": engine_error,
        "denied_tool_calls": denied,
        "pr_url": None,
    }
    (out / "record.json").write_text(json.dumps(record, indent=2) + "\n")

    # Job summary
    summary_path = env.get("GITHUB_STEP_SUMMARY")
    if summary_path:
        with open(summary_path, "a") as fh:
            fh.write(f"## The Agent: issue #{number}\n\n**{title}**\n\nOutcome: `{outcome}`\n\n{table}\n")

    github_output = env.get("GITHUB_OUTPUT")
    if github_output:
        with open(github_output, "a") as fh:
            fh.write(f"outcome={outcome}\n")
    print(f"Outcome: {outcome}")


def main(argv):
    if len(argv) < 2:
        fail(__doc__)
    command, args = argv[1], argv[2:]
    if command == "check-issue" and len(args) == 1:
        cmd_check_issue(args[0])
    elif command == "tier" and len(args) == 1:
        cmd_tier(args[0])
    elif command == "prompt" and len(args) == 2:
        cmd_prompt(args[0], args[1])
    elif command == "engine-summary" and not args:
        cmd_engine_summary()
    elif command == "report" and not args:
        cmd_report()
    else:
        fail(f"Unknown command or wrong arguments: {' '.join(argv[1:])}")


if __name__ == "__main__":
    main(sys.argv)
