# The Agent (runner v0.3.2)

The Agent fixes a GitHub issue in this repository and opens a pull request, without a human in the loop until review.

It runs as a GitHub Actions workflow (`.github/workflows/the-agent.yml`).

## How to run it

**By hand:** go to **Actions → The Agent → Run workflow** and fill in:

| Input | What it means |
|---|---|
| Issue number | The issue to fix |
| Engine | The coding engine: `claude` (Claude Code) or `gemini` (Gemini CLI) |
| Tier | How much model and process the run gets (see below) |
| Base | The branch to work on and open the PR against. `main` normally; `eval/baseline` for comparison runs |

**From the terminal:**

```bash
gh workflow run the-agent.yml --repo rmohamm/freightline -f issue=3 -f engine=claude -f tier=standard
```

**With a label:** add the `agent-ready` label to an issue. The run uses `claude`, `standard`, and `main`.

### Tiers

| Tier | Claude model | Gemini model | Superpowers process | Turn budget |
|---|---|---|---|---|
| `fast` | Haiku | Flash | test-driven development, verification | 40 |
| `standard` | Sonnet | Flash | + systematic debugging | 80 |
| `deep` | Opus | Pro | + a written plan before coding | 120 |

The process for each tier is the same for every engine, so a comparison only varies the engine. Gemini's free API tier only serves Flash, so `deep` on Gemini needs billing enabled on the Google Cloud project behind the API key. Once it is, `standard` can move to Pro in `ENGINE_MODELS` in `runner.py`.

### Engines

| | Claude Code | Gemini CLI |
|---|---|---|
| Credential secret | `CLAUDE_CODE_OAUTH_TOKEN` or `ANTHROPIC_API_KEY` | `GEMINI_API_KEY` |
| Project rules | `CLAUDE.md` (which imports `AGENTS.md`) | `AGENTS.md`, via `context.fileName` |
| Superpowers skills | copied to `.claude/skills/` | copied to `~/.gemini/skills/` (same `SKILL.md` files) |
| Tool allow-list | `--allowedTools`: read, edit, write, search, `mvn` | `tools.core` in `~/.gemini/settings.json`: file tools, todos, skills, and the shell only for `mvn` |
| Approvals | `dontAsk`: anything not allowed is denied | YOLO: everything that exists is approved; the allow-list decides what exists |
| Turns reported | conversation turns | API requests |
| Cost reported | estimated by Claude Code | estimated by the runner from token counts and the published prices in `GEMINI_PRICES` (`runner.py`); update that table when Google changes prices |

## What a run does

1. Checks out the code to work on (from the base branch) into `work/`, and the runner itself (always from `main`) into `runner/`. The runner sits outside the agent's working folder, so the agent can't read or change it.
2. Reads the issue and posts a "working on it" comment. The issue must be open, except on comparison runs against a baseline branch.
3. Checks that the base branch passes its tests before changing anything.
4. Copies the tier's skills into `.claude/skills/` and builds the prompt from `prompt.md`.
5. Runs the engine headless with only its own credential. It can read and edit files and run `mvn`, and nothing else. It has no GitHub token and no other engine's credential. The workflow times every engine itself, so engine time is comparable across engines.
6. Summarizes the engine's result: any error message and any tool calls the allow-list blocked appear as annotations on the run page and in the run summary. Each engine has an adapter in `runner.py` that reads its output; any engine's exit code and error output are saved, so even an unfamiliar failure explains itself.
7. Runs the full test suite again itself, rather than trusting the engine's word.
8. Decides the outcome:

   | Outcome | What happens |
   |---|---|
   | `pr_ready` | Code changed and the suite passes: opens a pull request |
   | `pr_draft` | Code changed but the suite fails: opens a **draft** pull request with the failures |
   | `needs_info` | The issue is too vague: comments with the agent's questions, no code change |
   | `no_change` | The agent gave up or changed nothing: comments with its report |
   | `blocked` | The agent touched `.github/`, `.agent/`, `.claude/` or `.gemini/`: no pull request, the job fails |
   | `engine_error` | The engine crashed or produced no result: the job fails |

   Comments for the outcomes without a pull request include the same run details (engine, tier, time, cost) as a PR.

9. Uploads a **run record** (`record.json` plus the prompt, report, engine output, and test log) as a workflow artifact. These records are the data for comparing engines, tiers, and processes.

## Branches

Each run gets its own branch, so runs with different engines, tiers, or bases sit side by side instead of overwriting each other:

- `the-agent/issue-3-claude-standard`: issue 3, Claude, standard tier, on `main`
- `the-agent/issue-3-claude-standard-on-eval-baseline`: the same, as a comparison run on `eval/baseline`

A rerun with the same settings updates the same branch and pull request.

## Comparison runs

`eval/baseline` holds the original Freightline code with all eight seeded bugs, from before any agent fix was merged. Running against it means every engine and tier faces identical code, even for issues that have since been fixed and closed on `main`. Its pull requests target `eval/baseline`, never `main`, and are for comparison only. Don't merge them.

## What it needs

Repository secrets:

| Secret | What it is |
|---|---|
| `AGENT_APP_ID` | The App ID of The Agent's GitHub App |
| `AGENT_APP_PRIVATE_KEY` | The app's private key (the full `.pem` contents) |
| `CLAUDE_CODE_OAUTH_TOKEN` or `ANTHROPIC_API_KEY` | The Claude engine's credential. If both are set, the API key is used |
| `GEMINI_API_KEY` | The Gemini engine's credential, from [Google AI Studio](https://aistudio.google.com/app/apikey) |

Stray spaces and line breaks in credentials are removed automatically. Only the secrets for the engines you use are needed.

The GitHub App needs **Contents**, **Issues**, and **Pull requests** set to read and write, and must be installed on this repository.

## Files

| Path | Purpose |
|---|---|
| `prompt.md` | The engine's instructions for unattended work (the issue text is inserted at the end, marked as untrusted) |
| `runner.py` | Tier and engine settings, prompt building, engine adapters, outcome decision, PR body, run record. Standard-library Python |
| `skills/` | Superpowers skills, vendored at a pinned commit. See `skills/SOURCE.md` |

## Safety

- The engine never holds GitHub credentials; only the workflow's own steps do.
- The engine's tools are an allow-list: read, edit, write, search, and `mvn`.
- The runner, prompt template, and skill sources are outside the engine's working folder.
- Gemini runs with workspace trust so its settings apply; the run refuses to start if the repository contains its own `.gemini/` folder, which could otherwise widen the allow-list.
- The issue text is treated as untrusted input.
- The `agent-ready` label can only be added by people with triage or write access to the repository.
- The Agent can open pull requests but can't merge them. Protect `main` so every change needs review and passing CI.
- Each run has a turn budget, and the engine step is capped at 35 minutes.

## Triage

`triage.py` and the **The Agent – triage** workflow ask a *judge* model three questions about each issue. There are two judges, and they get the same questions, the same project description, and the same routing rules, so their calls can be compared directly.

| Judge | What it is | Needs |
|---|---|---|
| `haiku` (default) | Claude Haiku through Claude Code, with every tool disabled, run from an empty folder, and its reply held to a JSON schema. Gives a short reason for each call | `CLAUDE_CODE_OAUTH_TOKEN` or `ANTHROPIC_API_KEY` (already used by the agent) |
| `jev` | [Jev](https://vercel.com/docs/ai-gateway/modalities/evaluation), TypeSafe AI's evaluation model, through Vercel AI Gateway | `AI_GATEWAY_API_KEY`, and **paid** AI Gateway credits (the free tier can't call Jev) |

The questions:

| Question | Answer |
|---|---|
| `ready` | Probability the issue is specific enough for an unattended agent to reproduce with a test |
| `complexity` | trivial, small, moderate, or large |
| `tier` | `fast`, `standard`, or `deep`, with a confidence |

The routing call is `needs_info` when P(ready) is under 50%, otherwise the chosen tier. A tier pick under 60%, or a readiness probability near 50%, is flagged as uncertain. Jev's probabilities are measured; Haiku's confidences are its own estimates, so treat its flags as rougher.

Along with each issue, the judge gets a short description of the project, taken from the repository's own `AGENTS.md`: its introduction plus any layout, architecture, or build sections (`CLAUDE.md` or `README.md` if there's no `AGENTS.md`). Nothing about Freightline is written into the script, so it works on any repository that describes itself for agents. The description used is saved in the triage record.

This step is **triage only**: it posts nothing to GitHub and starts no engine. It exists to measure whether the calls are good before they're allowed to choose a tier for a real run.

Run it from **Actions → The Agent – triage → Run workflow** with any issue numbers, as a list, a range, or both (`12`, `3,17,40`, `20-45`, `1-3,7`; up to 51 numbers per range), and a judge. Numbers that belong to pull requests, or that don't exist, are skipped with a warning. Or from the terminal:

```bash
gh workflow run the-agent-triage.yml --repo rmohamm/freightline -f issues=1-8 -f judge=haiku
```

The results appear as annotations and a table on the run page (with Haiku's reasons under it), and as a `triage.json` artifact with every answer, token count, and cost.

| | Haiku | Jev |
|---|---|---|
| Time per issue | about 20–30 seconds | under a second |
| Cost per issue | about $0.02–0.04 at list price (counts against your Claude subscription with the OAuth token) | well under $0.0001 |

If the judge's credential or account is refused, triage stops after the first call instead of repeating the error for every issue.
