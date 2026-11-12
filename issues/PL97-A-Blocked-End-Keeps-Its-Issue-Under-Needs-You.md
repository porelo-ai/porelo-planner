# PL97 — A Blocked End Keeps Its Issue Under Needs You

```json
{
  "$schema": "issue-1.json",
  "id": "PL97",
  "title": "A Blocked End Keeps Its Issue Under Needs You",
  "state": "Backlog",
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
- **The contract.** `TaskEnded` gains `outcome`, optional, `DONE` or `BLOCKED`, as Porelo's task row declares it,
  copied unchanged by the window; absent means as today. Written first in `planner.yaml`, covered by
  `ApiMatchesDescriptionTest`.
- **The judging.** `WorkEnds.Outcome` gains BLOCKED, taken whenever the declared outcome is BLOCKED, whatever
  `finished` says. `NextStep.after` treats BLOCKED as it treats FAILED: no move, no next task, the issues raised with
  attention `BLOCKED` and the why the window reported shown with the issue, as FAILED's why is today. The ended record
  keeps the outcome, so a replayed end matches its fingerprint and an older record without it reads as before.
- **Tests, red first.**
  - Unit, core (`WorkEndsTest`, `NextStepTest`, `WorkRunTest`): a build that finished declaring BLOCKED moves nothing,
    offers no reader and raises its issues with its why; DONE declared moves as today; no outcome behaves as today; a
    reader declaring BLOCKED does not take its reading as clean. Harm: work that said it could not be done moving on as
    done, a reading skipped.
  - Integration, app (`WorkRunHttpTest`): ENDED with `outcome: BLOCKED` keeps the issue in its state with `needsYou`
    and its attention; a malformed outcome is refused; both against the description.

## Acceptance

- A blocked end leaves its issues in their state under Needs you with the task's why; DONE and an end without an
  outcome behave as today; each new test seen red; the suite and the documents' checks pass.

## To be checked

- The name and values the window reads from Porelo's task row for the declared outcome, before the contract's field
  is fixed; agreed with the window's agent in the channel.
