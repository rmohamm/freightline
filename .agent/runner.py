#!/usr/bin/env python3
"""Helper for The Agent workflow (.github/workflows/the-agent.yml).

Subcommands:
  check-issue ISSUE_JSON [yes|no] fail unless the issue is an open issue (not a PR); "yes" also allows closed
  tier TIER ENGINE                print tier settings for that engine as key=value lines for $GITHUB_OUTPUT
  gemini-settings TIER            print the Gemini CLI settings.json for a run
  prompt ISSUE_JSON TIER          print the engine prompt built from prompt.md
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
RUNNER_VERSION = "v0.3.2"

# Tier -> process depth (Superpowers skills, turn budget, plan first). The same for every engine,
# so a comparison between engines only varies the engine.
TIERS = {
    "fast": {
        "max_turns": 40,
        "skills": ["test-driven-development", "verification-before-completion"],
        "plan": False,
    },
    "standard": {
        "max_turns": 80,
        "skills": ["test-driven-development", "systematic-debugging", "verification-before-completion"],
        "plan": False,
    },
    "deep": {
        "max_turns": 120,
        "skills": ["test-driven-development", "systematic-debugging", "verification-before-completion"],
        "plan": True,
    },
}

# Engine -> model for each tier.
# Gemini's free API tier only serves Flash, so `deep` (Pro) needs billing enabled on the Google Cloud project.
ENGINE_MODELS = {
    "claude": {"fast": "haiku", "standard": "sonnet", "deep": "opus"},
    "gemini": {"fast": "flash", "standard": "flash", "deep": "pro"},
}

# Gemini CLI's built-in tools that a run may use. Anything else (web search, web fetch, memory, other
# shell commands) doesn't exist for the model. Mirrors the Claude allow-list in the workflow.
GEMINI_TOOLS = ["read_file", "read_many_files", "write_file", "replace", "glob", "grep_search",
                "list_directory", "write_todos", "activate_skill", "run_shell_command(mvn)"]

PROTECTED_PREFIXES = (".github/", ".agent/", ".claude/", ".gemini/")
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


def short(text, limit=160):
    """First line of a command or message, trimmed for tables and annotations."""
    lines = str(text).strip().splitlines()
    first = lines[0] if lines else ""
    if len(first) > limit:
        return first[: limit - 1] + "…"
    return first + (" …" if len(lines) > 1 else "")


# --- check-issue -------------------------------------------------------------

def cmd_check_issue(issue_path, allow_closed="no"):
    issue = load_json(issue_path)
    if not issue:
        fail(f"Could not read the issue from {issue_path}.")
    if "pull_request" in issue:
        fail(f"#{issue['number']} is a pull request, not an issue.")
    if issue.get("state") != "open" and allow_closed != "yes":
        fail(f"Issue #{issue['number']} is {issue.get('state')}; The Agent only works on open issues "
             "(closed issues are allowed only on comparison runs against a baseline branch).")
    print(f"Issue #{issue['number']} ({issue.get('state')}): {issue['title']}")


# --- tier ------------------------------------------------------------------

def get_tier(name):
    if name not in TIERS:
        fail(f"Unknown tier '{name}'. Use one of: {', '.join(TIERS)}.")
    return TIERS[name]


def get_model(engine, tier_name):
    if engine not in ENGINE_MODELS:
        fail(f"Unknown engine '{engine}'. Use one of: {', '.join(ENGINE_MODELS)}.")
    return ENGINE_MODELS[engine][tier_name]


def cmd_tier(name, engine):
    tier = get_tier(name)
    print(f"model={get_model(engine, name)}")
    print(f"max_turns={tier['max_turns']}")
    print(f"skills={','.join(tier['skills'])}")


def cmd_gemini_settings(tier_name):
    """User-level Gemini CLI settings for a run: API-key auth, AGENTS.md as context, a tool allow-list, a turn cap."""
    tier = get_tier(tier_name)
    settings = {
        "security": {"auth": {"selectedType": "gemini-api-key"}},
        "context": {"fileName": ["AGENTS.md"]},
        "model": {"maxSessionTurns": tier["max_turns"]},
        "tools": {"sandbox": False, "core": GEMINI_TOOLS},
    }
    print(json.dumps(settings, indent=2))


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


# --- engine adapters -------------------------------------------------------
#
# Each engine reports its result in its own format. An adapter turns that into one common shape:
#   {ok, error, turns, duration_ms, cost, tokens, denied, exit_code}
# Everything after this point (outcome, PR body, run record) only uses the common shape.
# The workflow also times every engine itself (engine-duration-ms), used when an engine doesn't report it.

def read_int(out, name):
    text = read_text(out / name).strip()
    return int(text) if text.lstrip("-").isdigit() else None


def read_exit_code(out):
    return read_int(out, "engine-exit-code")


def stderr_tail(out, lines=5):
    tail = read_text(out / "engine-stderr.log").strip().splitlines()[-lines:]
    return " | ".join(l.strip() for l in tail if l.strip())


def generic_result(out):
    """Fallback for any engine: judge by exit code and stderr alone."""
    exit_code = read_exit_code(out)
    error = None
    if exit_code is None:
        error = "The engine produced no result (it may have crashed or been stopped before finishing)."
    elif exit_code != 0:
        tail = stderr_tail(out)
        error = f"The engine exited with code {exit_code}" + (f": {tail}" if tail else ".")
    return {"ok": error is None, "error": error, "turns": None, "duration_ms": read_int(out, "engine-duration-ms"),
            "cost": None, "tokens": None, "denied": [], "exit_code": exit_code}


def claude_result(out):
    # Claude Code reports in-run failures (bad credentials, API errors) in its JSON result on stdout,
    # not on stderr, so the error message has to be read from there.
    raw = load_json(out / "engine-result.json")
    if not raw:
        return generic_result(out)
    error = None
    if raw.get("is_error"):
        message = str(raw.get("result") or "").strip()
        detail = raw.get("api_error") or raw.get("terminal_reason") or raw.get("subtype")
        error = f"{message} ({detail})" if message and detail else (message or str(detail or "Unknown engine error."))
    denied = []
    for item in raw.get("permission_denials") or []:
        tool = item.get("tool_name", "?")
        tool_input = item.get("tool_input") or {}
        detail = tool_input.get("command") or tool_input.get("file_path") or tool_input.get("path") or ""
        denied.append(f"{tool}: {short(detail)}" if detail else tool)
    usage = raw.get("usage") or {}
    tokens = sum(usage.get(k) or 0 for k in ("input_tokens", "output_tokens",
                                             "cache_creation_input_tokens", "cache_read_input_tokens")) or None
    cost = raw.get("total_cost_usd")
    return {"ok": error is None, "error": error, "turns": raw.get("num_turns"),
            "duration_ms": raw.get("duration_ms") or read_int(out, "engine-duration-ms"),
            "cost": cost if isinstance(cost, (int, float)) else None,
            "tokens": tokens, "denied": denied, "exit_code": read_exit_code(out)}


def find_json_object(text):
    """The last JSON object embedded in free text (Gemini prints its error JSON to stderr, between log lines)."""
    decoder = json.JSONDecoder()
    found = None
    for i, ch in enumerate(text):
        if ch != "{":
            continue
        try:
            value, _ = decoder.raw_decode(text[i:])
        except json.JSONDecodeError:
            continue
        if isinstance(value, dict) and ("error" in value or "response" in value):
            found = value
    return found


# Gemini API standard paid-tier prices in USD per million tokens, as published at
# https://ai.google.dev/gemini-api/docs/pricing (checked October 2026; text input; Pro at its <=200k-token rate).
# Gemini CLI also calls small helper models (Flash-Lite) in the background, so they need prices too.
# The 3.6-3.8 Flash prices double on January 1, 2027: update this table then.
GEMINI_PRICES = {
    "gemini-3.8-flash": {"input": 0.75, "cached": 0.075, "output": 3.75},
    "gemini-3.7-flash": {"input": 0.75, "cached": 0.075, "output": 3.75},
    "gemini-3.6-flash": {"input": 0.75, "cached": 0.075, "output": 3.75},
    "gemini-3.5-flash": {"input": 1.50, "cached": 0.15, "output": 9.00},
    "gemini-3.5-flash-lite": {"input": 0.30, "cached": 0.03, "output": 2.50},
    "gemini-3.1-flash-lite": {"input": 0.25, "cached": 0.025, "output": 1.50},
    "gemini-3-flash-preview": {"input": 0.50, "cached": 0.05, "output": 3.00},
    "gemini-3.1-pro": {"input": 2.00, "cached": 0.20, "output": 12.00},
}


def gemini_price(model_name):
    """Price for a model ID, matching the longest known prefix (so "gemini-3.5-flash-lite" isn't priced as Flash)."""
    matches = [prefix for prefix in GEMINI_PRICES if model_name.startswith(prefix)]
    return GEMINI_PRICES[max(matches, key=len)] if matches else None


def gemini_usage(stats):
    """Token totals by type, per model, and a cost estimate.

    Gemini CLI counts `prompt` as all input including cached input, `cached` as the cached part, `input`
    as the uncached part, `candidates` as output, `thoughts` as thinking (billed as output), and `tool` as
    extra input for tool use. Models missing from GEMINI_PRICES are listed in `unpriced_models`, and the
    estimate then covers only the priced models.
    """
    usage = {"requests": 0, "uncached_input": 0, "cached_input": 0, "output": 0, "thinking": 0, "tool_input": 0,
             "total": 0, "models": {}, "unpriced_models": []}
    cost = 0.0
    for name, model in ((stats or {}).get("models") or {}).items():
        api = model.get("api") or {}
        tok = model.get("tokens") or {}
        cached = tok.get("cached") or 0
        uncached = tok.get("input") if tok.get("input") is not None else max(0, (tok.get("prompt") or 0) - cached)
        out, thoughts, tool = tok.get("candidates") or 0, tok.get("thoughts") or 0, tok.get("tool") or 0
        requests = api.get("totalRequests") or 0
        usage["requests"] += requests
        usage["uncached_input"] += uncached
        usage["cached_input"] += cached
        usage["output"] += out
        usage["thinking"] += thoughts
        usage["tool_input"] += tool
        usage["total"] += tok.get("total") or 0
        price = gemini_price(name)
        model_cost = None
        if price is None:
            usage["unpriced_models"].append(name)
        else:
            model_cost = ((uncached + tool) * price["input"] + cached * price["cached"]
                          + (out + thoughts) * price["output"]) / 1_000_000
            cost += model_cost
        usage["models"][name] = {"requests": requests, "tokens": tok.get("total") or 0,
                                 "cost": round(model_cost, 4) if model_cost is not None else None}
    return usage, (round(cost, 4) if usage["requests"] and len(usage["unpriced_models"]) < len(usage["models"]) else None)


def count_rate_limit_retries(stderr):
    """How often Gemini CLI waited and retried because of a quota or rate limit (429).

    Gemini CLI logs each failed attempt as "Attempt N failed: <reason>", with the reason on the same line,
    for example "You exceeded your current quota". Network failures ("fetch failed") aren't counted.
    """
    return sum(1 for line in stderr.splitlines()
               if re.search(r"Attempt \d+ failed", line) and re.search(r"quota|rate.?limit|429|exhausted", line, re.I))


def gemini_result(out):
    # Gemini CLI's JSON output is {session_id, response, stats, error}. stats.models.<model> holds request
    # counts and token totals; stats.tools holds tool-call counts. Exit code 53 means the turn cap was hit.
    # When it fails early it prints the same JSON to stderr instead of stdout, so look there too.
    raw = load_json(out / "engine-result.json") or find_json_object(read_text(out / "engine-stderr.log"))
    exit_code = read_exit_code(out)
    if not raw:
        result = generic_result(out)
        if exit_code == 53:
            result["error"] = "The engine hit its turn limit before finishing (exit code 53)."
        return result
    error = None
    if raw.get("error"):
        err = raw["error"]
        if isinstance(err, dict):
            message = str(err.get("message") or "").strip()
            details = ", ".join(str(x) for x in (err.get("type"), f"code {err['code']}" if err.get("code") else None) if x)
            error = f"{message} ({details})" if message and details else (message or details or "Unknown engine error.")
        else:
            error = str(err)
    elif exit_code == 53:
        error = "The engine hit its turn limit before finishing (exit code 53)."
    elif exit_code not in (0, None):
        tail = stderr_tail(out)
        error = f"The engine exited with code {exit_code}" + (f": {tail}" if tail else ".")
    stats = raw.get("stats") or {}
    usage, cost = gemini_usage(stats)
    usage["rate_limit_retries"] = count_rate_limit_retries(read_text(out / "engine-stderr.log"))
    failed_tools = (stats.get("tools") or {}).get("totalFail") or 0
    denied = [f"{failed_tools} tool call(s) failed. Gemini doesn't report which, or whether the allow-list refused them"] \
        if failed_tools else []
    return {"ok": error is None, "error": error, "turns": usage["requests"] or None,
            "duration_ms": read_int(out, "engine-duration-ms"),
            "cost": cost, "tokens": usage["total"] or None, "usage": usage if usage["requests"] else None,
            "denied": denied, "exit_code": exit_code}


ENGINE_ADAPTERS = {"claude": claude_result, "gemini": gemini_result}


def engine_result(engine, out):
    return ENGINE_ADAPTERS.get(engine, generic_result)(out)


# --- engine-summary --------------------------------------------------------

def format_duration(ms):
    if not ms:
        return "n/a"
    seconds = int(ms / 1000)
    return f"{seconds // 60}m {seconds % 60:02d}s"


def cmd_engine_summary():
    """Print what the engine did, and raise annotations the run page and API can show."""
    out = Path(os.environ["OUT"])
    result = engine_result(os.environ.get("ENGINE", "claude"), out)

    lines = ["### Engine result", ""]
    if result["error"]:
        print(f"::error title=Engine error::{result['error']}")
        lines.append(f"**Error:** {result['error']}")
    else:
        lines.append(f"Finished in {format_duration(result['duration_ms'])} and {result['turns'] or '?'} turns.")
    if result["denied"]:
        print(f"::warning title=Blocked tool calls::{len(result['denied'])} blocked: "
              + "; ".join(result["denied"])[:900])
        lines += ["", f"**Blocked by the tool allow-list ({len(result['denied'])}):**", ""]
        lines += [f"- `{d}`" for d in result["denied"]]
    usage = result.get("usage")
    if usage:
        unpriced = usage.get("unpriced_models") or []
        if result["cost"] is None:
            cost = "unknown (no model in the price table)"
        else:
            cost = f"${result['cost']:.4f}" + (f", excluding unpriced {', '.join(unpriced)}" if unpriced else "")
        per_model = "; ".join(f"{name}: {m['requests']} requests" for name, m in (usage.get("models") or {}).items())
        breakdown = (f"{usage['requests']} requests ({per_model}); tokens: {usage['uncached_input']:,} input, "
                     f"{usage['cached_input']:,} cached input, {usage['output']:,} output, "
                     f"{usage['thinking']:,} thinking, {usage['tool_input']:,} tool input; estimated cost {cost}")
        print(f"::notice title=Engine usage::{breakdown}")
        lines += ["", f"**Usage:** {breakdown}"]
        retries = usage.get("rate_limit_retries") or 0
        if retries:
            note = (f"The engine was rate-limited {retries} time(s) and waited before retrying, so its engine time "
                    "includes waiting. Compare timings only between runs that weren't throttled.")
            print(f"::warning title=Rate limited::{note}")
            lines += ["", f"**Rate limited:** {note}"]
    text = "\n".join(lines) + "\n"
    print(text)
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_path:
        with open(summary_path, "a") as fh:
            fh.write(text + "\n")


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
    model = get_model(engine, tier_name)
    base = env.get("BASE", "main")
    branch = env.get("BRANCH", "")
    run_url = env.get("RUN_URL", "")

    result = engine_result(engine, out)

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

    outcome = decide(result["ok"], status, files, bool(blocked_files), tests_passed)

    if tests:
        test_line = (f"{'Passed' if tests_passed else 'FAILED'}: {tests['run']} tests, "
                     f"{tests['failures']} failures, {tests['errors']} errors")
    else:
        test_line = "Passed" if tests_passed else "FAILED (no test summary found)"
    usage = result.get("usage") or {}
    if result["cost"] is None:
        cost_line = "n/a"
    elif usage.get("unpriced_models"):
        cost_line = f"${result['cost']:.2f}+ (estimate; excludes {', '.join(usage['unpriced_models'])})"
    else:
        cost_line = f"${result['cost']:.2f} (estimate)"
    turns = result["turns"] if result["turns"] is not None else "n/a"
    tokens_line = f"{result['tokens']:,}" if result["tokens"] else "n/a"

    facts = [
        ("Engine", f"{engine} (`{model}`)"),
        ("Tier", f"{tier_name}: {', '.join(tier['skills'])}"),
        ("Base branch", f"`{base}`"),
        ("Agent's status", status),
        ("Independent test run", test_line),
        ("Changes", f"{len(files)} files, +{additions} / -{deletions}"),
        ("Engine time", f"{format_duration(result['duration_ms'])}, {turns} turns"
                        + (f" (rate-limited {usage['rate_limit_retries']}x)" if usage.get("rate_limit_retries") else "")),
        ("Tokens", tokens_line),
        ("Cost", cost_line),
    ]
    table = "| | |\n|---|---|\n" + "\n".join(f"| {k} | {v} |" for k, v in facts)

    number = issue.get("number", env.get("ISSUE", "?"))
    title = issue.get("title", "")
    denied_section = []
    if result["denied"]:
        denied_section = ["## Blocked tool calls", "",
                          "The engine tried these, and the tool allow-list blocked them:", ""]
        denied_section += [f"- `{d}`" for d in result["denied"]] + [""]
    footer = ["---", f"<sub>Generated by The Agent runner {RUNNER_VERSION}. "
              "A human must review and approve before merging.</sub>"]

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
    lines += denied_section + footer
    (out / "pr-body.md").write_text("\n".join(lines) + "\n")

    # Issue comment, for outcomes that don't open a PR. Each one carries the same facts table.
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
                   f"Error: {result['error']}"]
    else:
        comment = []
    if comment:
        comment += ["", "<details><summary>Run details</summary>", "", table, "", "</details>", ""]
        comment += denied_section
    (out / "comment.md").write_text("\n".join(comment) + "\n")

    # Pull request title and commit message
    run_label = f"{engine}/{tier_name}" + ("" if base == "main" else f" on {base}")
    pr_title = f"Fix #{number}: {title} [{run_label}]"
    (out / "pr-title.txt").write_text(pr_title + "\n")
    commit = [f"Fix #{number}: {title}", "",
              f"Engine: {engine} ({tier_name} tier, model {model})",
              f"Base: {base}",
              f"Run: {run_url}"]
    (out / "commit-msg.txt").write_text("\n".join(commit) + "\n")

    # Run record (the data for the comparison table)
    record = {
        "runner_version": RUNNER_VERSION,
        "run_id": env.get("GITHUB_RUN_ID"),
        "run_url": run_url,
        "trigger": env.get("TRIGGER", "workflow_dispatch"),
        "issue": number,
        "issue_title": title,
        "engine": engine,
        "tier": tier_name,
        "model": model,
        "skills": tier["skills"],
        "base": base,
        "branch": branch,
        "outcome": outcome,
        "agent_status": status,
        "tests_passed": tests_passed,
        "tests": tests,
        "files_changed": files,
        "additions": additions,
        "deletions": deletions,
        "blocked_files": blocked_files,
        "engine_exit_code": result["exit_code"],
        "engine_duration_ms": result["duration_ms"],
        "engine_turns": result["turns"],
        "cost_usd_estimate": result["cost"],
        "tokens": result["tokens"],
        "token_usage": result.get("usage"),
        "engine_error": result["error"],
        "denied_tool_calls": result["denied"],
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
    if command == "check-issue" and len(args) in (1, 2):
        cmd_check_issue(*args)
    elif command == "tier" and len(args) == 2:
        cmd_tier(args[0], args[1])
    elif command == "gemini-settings" and len(args) == 1:
        cmd_gemini_settings(args[0])
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
