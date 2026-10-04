#!/usr/bin/env python3
"""The Agent: triage issues with Jev (TypeSafe AI) through Vercel AI Gateway.

Triage only. For each issue it asks Jev three typed questions and records the answers:

  ready       boolean  Is the issue specific enough for an unattended agent to reproduce?
  complexity  score    How much change a fix needs (trivial .. large)
  tier        choice   Which runner tier fits: fast, standard, or deep

From those it makes a routing call: `needs_info` when the issue isn't ready, otherwise the tier.
Nothing is posted to GitHub and nothing is run; this step only measures whether the calls are good.

Usage:
  triage.py OUT_DIR ISSUE_JSON [ISSUE_JSON ...]

Run it from the repository root. The project description Jev sees comes from the repository
itself (see project_context), so nothing here is specific to one codebase.

Needs AI_GATEWAY_API_KEY in the environment. Standard-library Python only.
Writes OUT_DIR/triage.json, prints a GitHub annotation per issue, and appends a table to
$GITHUB_STEP_SUMMARY when it is set.
"""

import json
import os
import re
import sys
import time
import urllib.error
import urllib.request

TRIAGE_VERSION = "v0.1"
ENDPOINT = "https://ai-gateway.vercel.sh/v1/evaluate"
MODEL = "typesafe-ai/jev"
MAX_BODY_CHARS = 8000          # issue bodies longer than this are cut, and the record says so
READY_THRESHOLD = 0.5          # P(ready) at or above this counts as ready
LOW_CONFIDENCE = 0.6           # a tier pick below this probability is flagged for a human
RETRIES = 3                    # for rate limits (429) and gateway errors (5xx)
TIMEOUT_SECONDS = 60

MAX_CONTEXT_CHARS = 4000       # project description sent with every issue
CONTEXT_FILES = ("AGENTS.md", "CLAUDE.md", "README.md")   # first one found describes the project
CONTEXT_HEADINGS = ("layout", "structure", "architecture", "overview", "modules", "packages",
                    "stack", "build")

# The questions are generic: they describe the runner's tiers, not any particular issue.
QUESTIONS = {
    "ready": {
        "type": "boolean",
        "instructions": (
            "Could an unattended coding agent, with no one to ask, act on this issue? It must be able "
            "to tell what behavior is wrong or what is wanted, and write a test that reproduces it."
        ),
        "criteria": {
            "true": "the wrong or wanted behavior is stated concretely enough to reproduce with a test",
            "false": "vague or missing details: no clear expected behavior, steps, inputs, or scope",
        },
    },
    "complexity": {
        "type": "score",
        "instructions": "How much change would a correct fix need in this codebase?",
        "criteria": [
            "trivial: a one-line or single-expression fix in an obvious place",
            "small: a contained change to one method or class",
            "moderate: several classes, or a root cause that takes investigation to find",
            "large: cross-cutting change, a design decision, data model, concurrency, or performance work",
        ],
    },
    "tier": {
        "type": "choice",
        "instructions": (
            "Which tier should a coding agent use for this issue? Higher tiers cost more, "
            "so pick the lowest tier that is likely to produce a correct fix."
        ),
        "criteria": {
            "fast": "small model, lean process: a localized, clearly described fix with an obvious location",
            "standard": "mid-size model plus systematic debugging: the cause must be traced through a few "
                        "components, or the fix needs care to avoid breaking related behavior",
            "deep": "largest model, written plan first: cross-cutting or design-level work, trade-offs "
                    "between approaches, concurrency, data model, or performance",
        },
    },
}


def fail(message):
    print(f"::error title=Triage::{message}")
    sys.exit(1)


def load_issue(path):
    with open(path, encoding="utf-8") as f:
        issue = json.load(f)
    body = issue.get("body") or ""
    truncated = len(body) > MAX_BODY_CHARS
    return {
        "number": issue["number"],
        "title": issue.get("title", ""),
        "state": issue.get("state", ""),
        "labels": [label["name"] if isinstance(label, dict) else label for label in issue.get("labels", [])],
        "body": body[:MAX_BODY_CHARS],
        "truncated": truncated,
        "is_pull_request": "pull_request" in issue,
    }


def project_context(root="."):
    """Describe the project from its own agent instructions.

    Uses the first of CONTEXT_FILES that exists: its introduction (the text before the first
    "## " heading) plus any section about the code's layout, architecture, or build. If the file has no
    such section, the start of the file is used. Returns (text, source_file), or ("", None).
    """
    for name in CONTEXT_FILES:
        path = os.path.join(root, name)
        if not os.path.isfile(path):
            continue
        with open(path, encoding="utf-8") as f:
            text = f.read()
        sections = re.split(r"(?m)^(?=## )", text)
        intro, rest = sections[0], sections[1:]
        picked = [sec for sec in rest
                  if any(h in sec.splitlines()[0].lower() for h in CONTEXT_HEADINGS)]
        body = "\n\n".join([intro.strip()] + [sec.strip() for sec in picked]) if picked else text
        body = body.strip()
        if body:
            return body[:MAX_CONTEXT_CHARS], name
    return "", None


def build_request(issue, context):
    return {
        "model": MODEL,
        "state": {
            "project": context or "(no project description found)",
            "issue": {
                "title": issue["title"],
                "labels": issue["labels"],
                "body": issue["body"],
            },
        },
        "questions": QUESTIONS,
    }


def call_jev(payload, api_key):
    """POST to the evaluation API. Returns (response_json, seconds, attempts)."""
    data = json.dumps(payload).encode("utf-8")
    started = time.monotonic()
    for attempt in range(1, RETRIES + 1):
        req = urllib.request.Request(
            ENDPOINT,
            data=data,
            method="POST",
            headers={
                "Authorization": f"Bearer {api_key}",
                "Content-Type": "application/json",
                "User-Agent": f"the-agent-triage/{TRIAGE_VERSION}",
            },
        )
        try:
            with urllib.request.urlopen(req, timeout=TIMEOUT_SECONDS) as resp:
                body = json.loads(resp.read().decode("utf-8"))
                return body, time.monotonic() - started, attempt
        except urllib.error.HTTPError as e:
            text = e.read().decode("utf-8", "replace")
            retryable = e.code == 429 or e.code >= 500
            if retryable and attempt < RETRIES:
                time.sleep(2 ** attempt)
                continue
            raise RuntimeError(f"HTTP {e.code}: {error_message(text)}") from None
        except (urllib.error.URLError, TimeoutError) as e:
            if attempt < RETRIES:
                time.sleep(2 ** attempt)
                continue
            raise RuntimeError(f"network error: {getattr(e, 'reason', e)}") from None
    raise RuntimeError("no response")  # not reached


def error_message(text):
    try:
        body = json.loads(text)
    except ValueError:
        return text.strip()[:300] or "no details"
    err = body.get("error", body)
    if isinstance(err, dict):
        return str(err.get("message") or err)[:300]
    return str(err)[:300]


def to_float(value):
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def interpret(response):
    """Turn Jev's answers into a routing call."""
    answers = response.get("answers", {})
    ready = answers.get("ready", {})
    complexity = answers.get("complexity", {})
    tier = answers.get("tier", {})

    p_ready = to_float(ready.get("probability"))
    score = to_float(complexity.get("score"))
    levels = QUESTIONS["complexity"]["criteria"]
    level = levels[min(len(levels) - 1, max(0, round(score)))].split(":")[0] if score is not None else None

    tier_choice = tier.get("choice")
    tier_probs = tier.get("probabilities") or {}
    tier_conf = to_float(tier_probs.get(tier_choice)) if tier_choice else None

    if p_ready is None or tier_choice is None:
        route = "error"
    elif p_ready < READY_THRESHOLD:
        route = "needs_info"
    else:
        route = tier_choice

    flags = []
    if p_ready is not None and abs(p_ready - READY_THRESHOLD) < 0.2:
        flags.append("readiness uncertain")
    if tier_conf is not None and tier_conf < LOW_CONFIDENCE:
        flags.append("tier uncertain")
    if tier_choice and not tier_probs:
        flags.append("no tier probabilities")

    meta = response.get("providerMetadata", {}).get("gateway", {})
    usage = response.get("usage", {})
    return {
        "route": route,
        "ready_probability": p_ready,
        "complexity_score": score,
        "complexity_level": level,
        "tier": tier_choice,
        "tier_confidence": tier_conf,
        "tier_probabilities": tier_probs,
        "complexity_probabilities": complexity.get("probabilities") or {},
        "flags": flags,
        "model": response.get("model"),
        "final_provider": meta.get("routing", {}).get("finalProvider"),
        "cost": to_float(meta.get("cost")),
        "input_tokens": usage.get("inputTokens"),
        "output_tokens": usage.get("outputTokens"),
        "generation_id": meta.get("generationId"),
    }


def pct(value):
    return "–" if value is None else f"{value:.0%}"


def one_line(text):
    """For annotations: one line, and no '::' that would end the message early."""
    return " ".join(text.split()).replace("::", ": :")


def cell(text):
    """For the summary table: one line, with pipes escaped."""
    return " ".join(text.split()).replace("|", "\\|")


def triage(out_dir, issue_paths, api_key):
    os.makedirs(out_dir, exist_ok=True)
    context, context_source = project_context()
    if context_source:
        print(f"Project description: {context_source} ({len(context)} characters)")
    else:
        print("::warning title=Triage::No AGENTS.md, CLAUDE.md, or README.md found; "
              "Jev will judge the issues without a project description")
    results = []
    issues = sorted((load_issue(p) for p in issue_paths), key=lambda i: i["number"])
    for issue in issues:
        n = issue["number"]
        entry = {"issue": n, "title": issue["title"], "state": issue["state"],
                 "labels": issue["labels"], "body_truncated": issue["truncated"]}
        if issue["is_pull_request"]:
            entry.update(route="skipped", error="this number is a pull request, not an issue")
            print(f"::warning title=Issue #{n}::Skipped: it is a pull request, not an issue")
            results.append(entry)
            continue
        try:
            response, seconds, attempts = call_jev(build_request(issue, context), api_key)
            entry.update(interpret(response))
            entry.update(seconds=round(seconds, 2), attempts=attempts, raw=response)
        except RuntimeError as e:
            entry.update(route="error", error=str(e))
            print(f"::error title=Issue #{n}::Jev call failed: {one_line(str(e))}")
            results.append(entry)
            continue

        flags = f" ({', '.join(entry['flags'])})" if entry["flags"] else ""
        print(
            f"::notice title=Issue #{n} -> {entry['route']}::"
            f"ready {pct(entry['ready_probability'])}, "
            f"complexity {entry['complexity_level'] or '–'} "
            f"({entry['complexity_score'] if entry['complexity_score'] is not None else '–'}), "
            f"tier {entry['tier']} {pct(entry['tier_confidence'])}{flags}: {one_line(issue['title'])}"
        )
        results.append(entry)

    costs = [r["cost"] for r in results if r.get("cost") is not None]
    record = {
        "triage_version": TRIAGE_VERSION,
        "model": MODEL,
        "endpoint": ENDPOINT,
        "thresholds": {"ready": READY_THRESHOLD, "low_confidence": LOW_CONFIDENCE},
        "project_context_source": context_source,
        "project_context": context,
        "questions": QUESTIONS,
        "issues": results,
        "total_cost": round(sum(costs), 8) if costs else None,
        "errors": sum(1 for r in results if r["route"] == "error"),
    }
    with open(os.path.join(out_dir, "triage.json"), "w", encoding="utf-8") as f:
        json.dump(record, f, indent=2)

    summary = render_summary(record)
    with open(os.path.join(out_dir, "summary.md"), "w", encoding="utf-8") as f:
        f.write(summary)
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_path:
        with open(summary_path, "a", encoding="utf-8") as f:
            f.write(summary)
    print(summary)
    return record


def render_summary(record):
    lines = [
        f"## Jev triage ({record['triage_version']}, `{record['model']}`)",
        "",
        "| Issue | Route | Ready | Complexity | Tier (confidence) | Flags | Cost |",
        "|---|---|---|---|---|---|---|",
    ]
    for r in record["issues"]:
        if r["route"] in ("error", "skipped") and "error" in r:
            lines.append(f"| #{r['issue']} {cell(r['title'])} | **{r['route']}** | | | | {cell(r['error'])} | |")
            continue
        score = r.get("complexity_score")
        cost = r.get("cost")
        lines.append(
            f"| #{r['issue']} {cell(r['title'])} | **{r['route']}** | {pct(r.get('ready_probability'))} "
            f"| {r.get('complexity_level') or '–'} ({'–' if score is None else f'{score:.2f}'}) "
            f"| {r.get('tier') or '–'} ({pct(r.get('tier_confidence'))}) "
            f"| {', '.join(r.get('flags', [])) or ''} "
            f"| {'–' if cost is None else f'${cost:.6f}'} |"
        )
    total = record["total_cost"]
    lines += [
        "",
        f"Total cost: {'unknown' if total is None else f'${total:.6f}'}. "
        f"Route is `needs_info` when P(ready) < {record['thresholds']['ready']:.0%}; "
        f"a tier pick under {record['thresholds']['low_confidence']:.0%} is flagged as uncertain.",
        "",
    ]
    return "\n".join(lines)


def main(argv):
    if len(argv) < 3:
        fail("usage: triage.py OUT_DIR ISSUE_JSON [ISSUE_JSON ...]")
    api_key = "".join((os.environ.get("AI_GATEWAY_API_KEY") or "").split())
    if not api_key:
        fail("AI_GATEWAY_API_KEY is not set. Add it as a repository secret (Vercel → AI Gateway → API Keys).")
    record = triage(argv[1], argv[2:], api_key)
    if record["errors"]:
        fail(f"{record['errors']} of {len(record['issues'])} issues could not be triaged; see the annotations.")


if __name__ == "__main__":
    main(sys.argv)
