# The Agent (runner v0)

The Agent fixes a GitHub issue in this repository and opens a pull request, without a human in the loop until review.

It runs as a GitHub Actions workflow (`.github/workflows/the-agent.yml`) that you start by hand.

## How to run it

1. Go to **Actions → The Agent → Run workflow**.
2. Enter the **issue number** and pick a **tier**:

   | Tier | Model | Superpowers process | Turn budget |
   |---|---|---|---|
   | `fast` | Haiku | test-driven development, verification | 40 |
   | `standard` | Sonnet | + systematic debugging | 80 |
   | `deep` | Opus | + a written plan before coding | 120 |

3. Watch the run. The Agent comments on the issue when it starts and when it finishes.

## What a run does

1. Reads the issue (it must be open) and posts a "working on it" comment.
2. Checks that `main` passes its tests before changing anything.
3. Copies the tier's skills from `.agent/skills/` into `.claude/skills/` and builds the prompt from `.agent/prompt.md`.
4. Runs Claude Code headless. The engine can read and edit files and run `mvn`, and nothing else. It has no GitHub token.
5. Runs the full test suite again itself, rather than trusting the engine's word.
6. Decides the outcome:

   | Outcome | What happens |
   |---|---|
   | `pr_ready` | Code changed and the suite passes: opens a pull request |
   | `pr_draft` | Code changed but the suite fails: opens a **draft** pull request with the failures |
   | `needs_info` | The issue is too vague: comments with the agent's questions, no code change |
   | `no_change` | The agent gave up or changed nothing: comments with its report |
   | `blocked` | The agent touched `.github/`, `.agent/` or `.claude/`: no pull request, the job fails |
   | `engine_error` | The engine crashed or produced no result: the job fails |

7. Uploads a **run record** (`record.json` plus the prompt, report, and test log) as a workflow artifact. These records are the data for comparing engines, tiers, and processes.

Pull requests come from branch `the-agent/issue-<number>`, authored by The Agent's GitHub App. A rerun on the same issue updates the same branch and pull request.

## What it needs

Repository secrets:

| Secret | What it is |
|---|---|
| `AGENT_APP_ID` | The App ID of The Agent's GitHub App |
| `AGENT_APP_PRIVATE_KEY` | The app's private key (the full `.pem` contents) |
| `CLAUDE_CODE_OAUTH_TOKEN` or `ANTHROPIC_API_KEY` | The engine's model credential. If both are set, the API key is used |

The GitHub App needs **Contents**, **Issues**, and **Pull requests** set to read and write, and must be installed on this repository.

## Files

| Path | Purpose |
|---|---|
| `prompt.md` | The engine's instructions for unattended work (the issue text is inserted at the end, marked as untrusted) |
| `runner.py` | Tier settings, prompt building, outcome decision, PR body, run record. Standard-library Python |
| `skills/` | Superpowers skills, vendored at a pinned commit. See `skills/SOURCE.md` |

## Safety

- The engine never holds GitHub credentials; only the workflow's own steps do.
- The engine's tools are an allow-list: read, edit, write, search, and `mvn`.
- The issue text is treated as untrusted input.
- The Agent can open pull requests but can't merge them. Protect `main` so every change needs review and passing CI.
- Each run has a turn budget, and the engine step is capped at 35 minutes.
