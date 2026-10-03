# Vendored Superpowers skills

These skills are copied unmodified from [obra/superpowers](https://github.com/obra/superpowers)
at commit `8ca22dba9a94f28898bbce59f2537ff4d87c747d` (2026-09-25), under the MIT License (see `LICENSE`).

| Skill | Used in tiers |
|---|---|
| `test-driven-development` | fast, standard, deep |
| `verification-before-completion` | fast, standard, deep |
| `systematic-debugging` | standard, deep |

They are pinned on purpose: a Superpowers update can't change the agent's behavior between runs
unless someone updates this folder in a reviewed pull request.

The workflow copies the skills for the chosen tier into `.claude/skills/` at the start of each run.
That copy is never committed.
