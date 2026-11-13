# PL122 — A Rework That Ends Offers Its Reading

```json
{
  "$schema": "issue-1.json",
  "id": "PL122",
  "title": "A Rework That Ends Offers Its Reading",
  "state": "Review",
  "size": "S",
  "priority": "High",
  "rank": 58,
  "blockedBy": [],
  "covers": "A rework task that ended finished and landed moves its issue on to its reading as the rule's then says, a next step the planner cannot take is logged with one line of why instead of a bare NEXT_STEP_UNAVAILABLE, and every agent's order leaves the issues' states, their index and stats/ to the planner"
}
```

**Size:** S - the end of a work task in `NextStep`, the words of an unavailable next step in `NextStep` and `WorkRun`,
one line of every order in `WorkOrder`, the log line in the app's `Projects`, and their unit tests in `core`.

**Estimate:** 120 min, a guess: no median of S is measured.

**What must be true.** After a rework task ends finished with its work landed, the issue goes on to its reading as the
rule says, and a reader is offered. When the planner cannot take a next step, the log reads which issue, which story
and why. An agent's order says that the planner alone moves the issues, so a task leaves their state, the issues'
index and `stats/` alone.

## Plan

- **The cause.** At a task's end `NextStep.after` took the state the rule leads to, then refused the step unless the
  next rule's `from` named the state the task worked in - also for the rule's own `then`. With a Reading rule from
  Implementing and a Reworking rule then Reading, which the work-rules check calls sound, a rework's end could never
  move its issue on: it stayed in Reworking under NEXT_STEP_UNAVAILABLE with no word. The `then` move is made at the
  end, so the reader's start finds the issue in its own state and never asks `from`; this check was the only place
  that applied `from` to a `then`. A rule's `per` plays no part: only a story's order reads it, and every follow-up is
  a task of one issue.
- **The fix.** A rule's `then` is its own word for where its work goes on and passes without the next rule's `from`;
  a `blocked` state is entered as a start and still needs it, as the person's Rework choice and a start do.
- **The words.** Every place in `NextStep` and `WorkRun` that sets NEXT_STEP_UNAVAILABLE keeps one line of why per
  issue (`NextStep.Result.unavailable`, a run's draft); once the run is saved, `WorkRun` hands each line to the app,
  which logs it at INFO as `No next step in project <p>: Issue <i> of story <s>: <why>`. The row has no field for
  words: `PlanWorkIssue` holds a fixed reason only, so showing them there needs an optional string beside `reason` in
  `planner.yaml`, kept in the work record's progress and read by the screen's Needs-you row - a contract change
  with the window, not made here.
- **The order.** `WorkOrder.prompt` adds to every order that the planner alone moves the issues and writes their
  state, `issues/README.md` and `stats/`. It does not forbid `issues/` as a whole, since an analysis order asks its
  agent to write an issue's plan.
- **A landed build that moves its own issue.** Its commits stay on the landed ref, which the store does not read. Once
  they reach the store's main before the end, the board shows the agent's state, the end finds the issue moved and
  asks the person, writing nothing over it; now with the line of why.
- **Tests, unit, `WorkRunTest`, each seen red.** A rework under exactly the VM's rules, landed at its end or after it,
  moves its issue to Reading and offers a reader of the rework's build (red on the `from` check); an issue the store
  moved to Review meanwhile asks the person, writes nothing and is told in one line naming the issue, its story and
  both states (red with the line left empty); the build's, the reader's and the rework's orders each carry the
  planner's line (red with the line left out).

## Acceptance

- The unit test that reproduces the VM's end, seen red; on the VM the rework's end offers a reader and the issue
  stands in Reading.
