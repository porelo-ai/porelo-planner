# PL07 — The Work Rules Read From The Workflow

```json
{
  "$schema": "issue-1.json",
  "id": "PL07",
  "title": "The Work Rules Read From The Workflow",
  "state": "Backlog",
  "size": "S",
  "priority": "Medium",
  "rank": 6,
  "blockedBy": ["PL03"],
  "covers": "Which state starts which work read from `stories/work.yml` and checked, its faults answered beside the workflow"
}
```

**Why this size.** the work part of the workflow file read and checked, and answered with the workflow.

**What must be true.** Which state starts which work - its agent and model, its modes, its order, the state success
leads to, the next working state and the one blocking findings lead to, one task per story or per repository - is
read from the project's planning configuration. A fault in it (a state the workflow lacks, a working state without a
rule) is answered in words beside the workflow and never hides the board.

## Plan

- `stories/work.yml` in the project repository, a file of its own beside `stories/workflow.yml` (which every
  repository copies byte for byte, so the work rules stay out of it): per working state `from`, `done`, `then`,
  `blocked`, `per` (`story` or `repository`), `modes`, `order` (with `{story}`, `{issues}`, `{tasks}`), `model` by
  provider. The rules porelo's project file was to carry are the model.
- `core`: `WorkRules.check(workflow, rules)` - every key, `from`, `done`, `then` and `blocked` a state of the
  workflow; every working state a key; `done` not a working state.
- `GET /projects/{project}/workflow` answers `work` and `faults`.
- **Red first:** `WorkRulesTest` - a `done` that is a working state, a key the workflow lacks and a working state
  without a rule are each named.

## Acceptance

- The red-first test seen red, then green.
