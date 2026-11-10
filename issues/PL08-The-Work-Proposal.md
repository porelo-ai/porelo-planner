# PL08 — The Work Proposal

**Status:** Analyzed; blocked by [PL07](README.md#pl07).
**Size:** M - the proposal per repository from the work rules and the sub-issues, the operator's choices, and the
prompts the window starts each task with.

**What must be true.** For a story about to enter a working state, the planner proposes what to start: per
repository (or for the story) one main agent with its model and order, and each sub-issue as a task of its own with
its role, agent, model and brief. The operator keeps, changes or drops each sub-issue's task, adds a comment and sets
the reading limit for the story or single issues (default 2). The planner then answers, per task, everything the
window passes to Porelo's `Start`: repository, agent, model, mode and the prompt. A dropped sub-issue's brief goes into
the main agent's prompt as its own work. The planner starts nothing itself.

## Plan

- `POST /projects/{project}/stories/{story}/proposal` with `{to}` -> `{modes, tasks: [{repository, role, issue,
  agent, model, prompt}], comment, readingRounds, issueReadingRounds}`.
- `POST /projects/{project}/stories/{story}/work` with the choices `KEEP|CHANGE|DROP` per sub-issue, comment and
  limits, against the revision -> the final tasks and their prompts; refused with `work-not-allowed(from, to, reason)`
  when the story's state is not a `from` of the rule, and `work-running` when the story's work is recorded running.
- **The story enters the working state when the window reports its tasks started** (the task events of the run),
  never when the proposal is accepted; a start that fails leaves it where it was.
- `core`: `WorkProposal` and `WorkOrder` (main prompt: order, issues, comment, dropped briefs; sub-issue prompt:
  order, brief, comment).
- **Red first:** `WorkProposalTest` - two repositories give two main tasks and one task per sub-issue; a dropped
  sub-issue's brief lands in its main task's prompt; a `from` the rule lacks is refused.

## Acceptance

- The red-first test seen red, then green.
