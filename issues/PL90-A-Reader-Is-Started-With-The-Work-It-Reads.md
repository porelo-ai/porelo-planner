# PL90 — A Reader Is Started With The Work It Reads

```json
{
  "$schema": "issue-1.json",
  "id": "PL90",
  "title": "A Reader Is Started With The Work It Reads",
  "state": "Implementing",
  "size": "M",
  "priority": "Highest",
  "rank": 1,
  "blockedBy": [],
  "covers": "A reader's order names the branch each task it reads landed on, as Porelo reports it with that task's end, and the reader is started naming those tasks, so Porelo's gate gives the reader their work to read"
}
```

**Size:** M - one contract change between the window and the planner (the task's end and a new `LANDED` event
carry its branch, a reader's offer the tasks it reads), carried through `core` and `app`; the window's side and
Porelo's are built in their own repositories.

**Estimate:** 67 min, the measured median of M. Uncertain: how Porelo names the landed branch on a task's row.

**What must be true.** A reader offered after a build reads that build's work: its order names the branch the build's
work landed on, as Porelo reports it on the build's end, never a name the planner makes up; and its offer names the
tasks whose work it reads, so the window starts it naming them and Porelo's gate gives the reader those branches, read
only. Today the order names only the build's task, the reader's `origin` carries no other task's branch, and the
reader is offered at the build's end even while its work still waits at the gate.

## Plan

- **Today, read in the code.** A builder's end comes as `TaskEventRequest` ENDED with `TaskEnded` (at, finished, why,
  answer, unpushed); `WorkRun.report` judges it, and `NextStep.after` offers the reader at once with `{tasks}` filled
  by `NextStep.builtBy(task)`: "the work of Porelo task <t>", no branch (`WorkRun.lastBuild` finds the task again for
  a reading round taken up again). The offer goes to the window as `PlanningStartTask` (offer, repository, issues,
  role, agent, model, mode, prompt); nothing in it names the tasks a reader reads.
- **The task's end names its branch.** `TaskEnded` gains `branch`, optional: the branch Porelo reports the task's work
  landed on (Porelo's task row carries it once the work left the gate), checked as a Git branch name
  (no `..`, no control characters, at most 255 bytes) and kept on the offer's ended record (`WorkRun.Ended`).
- **The reader's order and offer name the build.** `{tasks}` reads "the work of Porelo task <t> on the branch <b>" from
  the recorded end, never a name the planner makes; `PlanningStartTask` gains `reads`, the task names of the builds the
  reader reads (`WorkOrder.Task`), so the window starts the reader naming them and Porelo's gate gives it those branches
  read only. A reading taken up again takes the issue's last build with its branch (`WorkRun.lastBuild`).
- **No reader before the work has landed.** ENDED comes as soon as the agent ends, and carries `branch` when the work
  has already left the gate. A build that ended done without a branch is judged as today and moves its issue to the
  reading state, but no reader is offered yet: the window reports a new event `LANDED` (offer, branch) for that task
  once its work has left the gate, and only then is the reader offered. `LANDED` for an offer that did not end done,
  already landed, or is unknown is refused (409), so a reader is offered once. The window's side is its own issue.
- **Tests, by level, red first.**
  - Unit, core (`NextStepTest`, `WorkRunTest`): a reader's order names the build's task and branch, its offer `reads`
    names that task; a build ended without a branch offers no reader until its `LANDED`, then
    one; a second `LANDED` is refused; a reading taken up again names the last build's branch. Harm: a reader started on nothing, which polls or reads the wrong work.
  - Integration, app: ENDED and `LANDED` with `branch` accepted and kept, a malformed branch refused; the offer answered with
    `reads`, both against the description (`ApiMatchesDescriptionTest`). Harm: the window and the planner reading the
    contract differently.

## Acceptance

- A reader offered after a build names that build's task and branch in its order and in `reads`; a build that ends
  before its work landed offers its reader on `LANDED`, never before; every new test seen red; the suite and the documents' checks pass.

## To be checked

- The name Porelo serves for the landed branch on a task's row and its value while the work still waits; the
  contract's own names (`reads`, `branch`, `LANDED`) stand, and the window maps Porelo's names onto them.
