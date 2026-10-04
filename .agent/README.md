# The Agent (runner v0.2)

The Agent fixes a GitHub issue in this repository and opens a pull request, without a human in the loop until review.

It runs as a GitHub Actions workflow (`.github/workflows/the-agent.yml`).

## How to run it

**By hand:** go to **Actions → The Agent → Run workflow** and fill in:

| Input | What it means |
|---|---|
| Issue number | The issue to fix |
| Engine | The coding engine (`claude` for now) |
| Tier | How much model and process the run gets (see below) |
| Base | The branch to work on and open the PR against. `main` normally; `eval/baseline` for comparison runs |

**From the terminal:**

```bash
gh workflow run the-agent.yml --repo rmohamm/freightline -f issue=3 -f engine=claude -f tier=standard
```

**With a label:** add the `agent-ready` label to an issue. The run uses `claude`, `standard`, and `main`.

### Tiers

| Tier | Claude model | Superpowers process | Turn budget |
|---|---|---|---|
| `fast` | Haiku | test-driven development, verification | 40 |
| `standard` | Sonnet | + systematic debugging | 80 |
| `deep` | Opus | + a written plan before coding | 120 |

The process for each tier is the same for every engine, so a comparison only varies the engine.

## What a run does

1. Checks out the code to work on (from the base branch) into `work/`, and the runner itself (always from `main`) into `runner/`. The runner sits outside the agent's working folder, so the agent can't read or change it.
2. Reads the issue and posts a "working on it" comment. The issue must be open, except on comparison runs against a baseline branch.
3. Checks that the base branch passes its tests before changing anything.
4. Copies the tier's skills into `.claude/skills/` and builds the prompt from `prompt.md`.
5. Runs the engine headless. It can read and edit files and run `mvn`, and nothing else. It has no GitHub token.
6. Summarizes the engine's result: any error message and any tool calls the allow-list blocked appear as annotations on the run page and in the run summary. Each engine has an adapter in `runner.py` that reads its output; any engine's exit code and error output are saved, so even an unfamiliar failure explains itself.
7. Runs the full test suite again itself, rather than trusting the engine's word.
8. Decides the outcome:

   | Outcome | What happens |
   |---|---|
   | `pr_ready` | Code changed and the suite passes: opens a pull request |
   | `pr_draft` | Code changed but the suite fails: opens a **draft** pull request with the failures |
   | `needs_info` | The issue is too vague: comments with the agent's questions, no code change |
   | `no_change` | The agent gave up or changed nothing: comments with its report |
   | `blocked` | The agent touched `.github/`, `.agent/` or `.claude/`: no pull request, the job fails |
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
| `CLAUDE_CODE_OAUTH_TOKEN` or `ANTHROPIC_API_KEY` | The Claude engine's credential. If both are set, the API key is used. Stray spaces and line breaks are removed automatically |

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
- The issue text is treated as untrusted input.
- The `agent-ready` label can only be added by people with triage or write access to the repository.
- The Agent can open pull requests but can't merge them. Protect `main` so every change needs review and passing CI.
- Each run has a turn budget, and the engine step is capped at 35 minutes.
