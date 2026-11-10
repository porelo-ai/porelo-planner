# PL07 — The Work Rules Read From The Workflow

**Status:** Analyzed; blocked by [PL03](README.md#pl03).
**Size:** S - the work part of the workflow file read and checked, and answered with the workflow.

**What must be true.** Which state starts which work - its agent and model, its modes, its order, the state success
leads to, the next working state and the one blocking findings lead to, one task per story or per repository - is
read from the project's planning configuration. A fault in it (a state the workflow lacks, a working state without a
rule) is answered in words beside the workflow and never hides the board.

## Plan

- `work:` beside `states:` and `phases:` in `stories/workflow.yml`: per working state `from`, `done`, `then`,
  `blocked`, `per` (`story` or `repository`), `modes`, `order` (with `{story}`, `{issues}`, `{tasks}`), `model` by
  provider. The rules porelo's project file was to carry are the model.
- `core`: `WorkRules.check(workflow, rules)` - every key, `from`, `done`, `then` and `blocked` a state of the
  workflow; every working state a key; `done` not a working state.
- `GET /projects/{project}/workflow` answers `work` and `faults`.
- **Red first:** `WorkRulesTest` - a `done` that is a working state, a key the workflow lacks and a working state
  without a rule are each named.

## Acceptance

- The red-first test seen red, then green.

## Open questions

- The work rules sit in `stories/workflow.yml`, which every repository copies byte for byte as
  `issues/workflow.yml`. Should they go there, or into a file of their own in the project repository
  (`stories/work.yml`)?
