# PL97 — A Blocked End Keeps Its Issue Under Needs You

```json
{
  "$schema": "issue-1.json",
  "id": "PL97",
  "title": "A Blocked End Keeps Its Issue Under Needs You",
  "state": "Review",
  "size": "M",
  "priority": "High",
  "rank": 34,
  "blockedBy": [],
  "covers": "A task that ends declaring its work BLOCKED, with why, keeps its issues where they stand, under Needs you with that why, instead of being taken as done and moved on"
}
```

**Size:** M - one contract change between the window and the planner (the task's end carries its declared outcome),
carried through the judging in `core` and the app, with unit tests over fakes and the HTTP tests.

**Estimate:** 67 min, the measured median of M.

**What must be true.** A task may end by declaring its work done or blocked, with why. A blocked end does not move its
issues on: they stay in their state, raised under Needs you, and the person reads the task's why. Today the planner
knows only whether the run finished and how many commits it left unpushed, so a task that declared itself blocked and
finished is judged done and its issue moves to the next state, as if the work were there. DONE and a run that did not
finish behave as today.

## Plan

- **Today, read in the code.** The window reports ENDED with `TaskEnded` (at, finished, why, answer, unpushed, landed);
  `WorkEnds.Outcome.of(finished, unpushed)` gives DONE, UNPUSHED or FAILED, and `NextStep.after` moves a DONE build's
  issues to the rule's `then` or `done` and offers the next task; FAILED and UNPUSHED raise the issues under Needs you.
- **The contract, agreed with the window.** `TaskEnded` gains `outcome`, optional, one of `DONE`, `BLOCKED` and
  `FAILED`, Porelo's `WorkEnded.outcome` copied unchanged by the window; absent means as today. There is no second
  reason field: the window fills the existing `why` from `WorkEnded.why`. Written first in `planner.yaml`, covered by
  `ApiMatchesDescriptionTest`. `TaskEnded` refuses a property it does not describe, so the window sends `outcome`
  only to a planner published with it.
- **The judging.** `WorkEnds.Outcome` gains BLOCKED, taken whenever the declared outcome is BLOCKED, whatever
  `finished` says, and FAILED whenever the declared outcome is FAILED. `NextStep.after` treats BLOCKED as it treats FAILED: no move, no next task, the issues raised with
  attention `BLOCKED` and the why the window reported shown with the issue, as FAILED's why is today. A declared DONE
  decides nothing by itself: an unfinished run is still FAILED and unpushed commits still UNPUSHED. The same judging
  holds where `WorkRun.landed` checks that a build ended done, so a blocked build's landing is refused as a conflict.
- **The record.** `WorkRun.Ended` keeps the outcome, through a canonical constructor declared in full, since the
  app's NullAway reads no nullness from a compact constructor's parameters, and every rebuild of it (the landing, the compaction of an older
  reading) carries it. The fingerprint adds the outcome only when one was declared, so an end recorded before keeps its
  print and a replay with another outcome is a conflict. `WorkRecord.tree` backfills an absent `ended.outcome` with
  null, so an older record reads as before. The answers echo the outcome in `PlanWorkTask.ended`, left out when none
  was declared, and `PlanWorkIssue.reason` and `Issue.attention` gain `BLOCKED`.
- **The screen.** `PlanningTaskEnded`, which the window fills from Porelo's end record, gains `outcome` and sends it only
  when set; `WorkEnded` reads it back, a declared BLOCKED or FAILED end counts as one needing attention, and BLOCKED
  has its words and opens the task's result like FAILED.
- **Tests, red first.**
  - Unit, core (`WorkEndsTest`, `WorkRunTest`; `NextStep` needs no change, since it raises any reason the judging
    gives): a build that finished declaring BLOCKED moves nothing,
    offers no reader and raises its issues with its why; DONE declared moves as today; no outcome behaves as today; a
    reader declaring BLOCKED does not take its reading as clean. Harm: work that said it could not be done moving on as
    done, a reading skipped.
  - Integration, app (`WorkRunHttpTest`): ENDED with `outcome: BLOCKED` keeps the issue in its state with `needsYou`
    and its attention; a malformed outcome is refused; both against the description.
  - Unit, core: a declared DONE over an unfinished or unpushed run is judged as without it; a landing after a blocked
    end is refused; a replayed blocked end offers nothing new, and one with another outcome is a conflict. Harm:
    unfinished work moving on, a reader started on blocked work, an end counted twice.
  - Integration, app (`ApiMatchesDescriptionTest`): the described outcomes are exactly those the planner takes, and
    both reason lists name every reason it raises; the old guard that `TaskEnded` has no outcome goes with the agreed
    contract. Harm: a window that sends an outcome the planner refuses, or refuses an answer naming BLOCKED.
  - Integration, app (`WorkRecordTest`): a record whose ended task has no outcome reads as before. Harm: a planner
    that cannot read its own record after the update.
  - Unit, screen: `PlanningTaskEnded` sends a set outcome and leaves an unset one out; a declared BLOCKED end raises
    attention. Harm: the window's outcome lost on the wire, so a blocked end is judged done.

## Acceptance

- A blocked end leaves its issues in their state under Needs you with the task's why; DONE and an end without an
  outcome behave as today; each new test seen red; the suite and the documents' checks pass.
