# PL43 — A Slow Save Told As Slow, Not As A Failure

```json
{
  "$schema": "issue-1.json",
  "id": "PL43",
  "title": "A Slow Save Told As Slow, Not As A Failure",
  "state": "Estimated",
  "size": "S",
  "priority": "Medium",
  "rank": 26,
  "blockedBy": [],
  "covers": "A save that takes longer than the screen waits is shown as still saving and, once the board shows it done, as saved; two messages never run together"
}
```

**Size:** S - the screen package alone (`PlanningBoard` and its tests); the planner and the contract stay as they are.

**Estimate:** 33 min, the measured median of S. Uncertain: if the move flow is first rewritten to one save per move
([PL38](README.md#pl38), ranked before this one), the same rules land in that flow's save instead of the batch's.

**What must be true.** A person who moves a story or an issue sees it being saved while the push takes its time, and
sees it saved once the board shows the move; a slow push is never told as a failure, and each message stands apart.

## Plan

Today, read in `screen/lib/src`:

- `HttpPlanningSource._call` (`http/planner_source.dart`) waits `timeout`, 30 s, aborts the request and throws
  `PlanningRefusal('TimedOut', ..., 'the planner did not answer within 30 s')`. The name `TimedOut` is only this
  deadline of the screen's own: the planner's own store timeout arrives as `Unreachable` (`Problems`, HTTP 504).
- The planner goes on with the save after the screen stops waiting: a save is a fetch, a commit and a push, each Git
  call bounded at 60 s (`StoreConfiguration.gitProjects`), behind a lock waited on up to 30 s (`GitStore.LOCK_WAIT`);
  `BoardController.saveMoves` calls `changes.saved(project)` in `finally`, so the stream sends the board afterwards.
- `PlanningBoard.save` (`planning_board.dart`) takes the `TimedOut` refusal as refused: `moveProblem` and each moved
  entry's `problem` get the words, and `_finishOperation(reconcile: false)`. The next streamed board goes through
  `_accept` with reconciliation, finds each pending move at its destination and appends `'<id> was moved to <to>
  elsewhere.'` to `moveProblem` with a space, so both run together in the one red line `planning-move-error` that
  `PlanningView` and `PlanningStoryView` show.

Steps, all in `PlanningBoard` and `PendingPlanningMove`:

1. `PendingPlanningMove` gains `unanswered`. In `save`, a `PlanningRefusal` named `TimedOut` is no refusal: each moved
   entry of the batch is marked `unanswered`, gets no `problem`, `moveProblem` stays empty, and `moveNotice` says the
   save is still running and the move shows as saved once the board shows it. `saving` ends, so the stream's boards
   are taken and reconciled.
2. In `_accept`, an `unanswered` entry the board reports at its destination is removed without a message; once no
   `unanswered` entry is left, `moveNotice` is the same `Issue changes saved.` or `Planning changes saved.` a
   confirmed save shows. An `unanswered` entry the board reports elsewhere stays pending, so Save is offered again: it
   carries the revision of the board shown, and the planner refuses it as `StaleRevision` if the slow push landed after
   that board, so it is never written twice.
3. `moveProblem` joins its parts with a line break instead of a space, in `_accept` and in the reread after
   `PartlySaved` or `StaleRevision`; the views' `Text` shows each on its own line.

Tests, unit level (`flutter test`, `test/planning_moves_test.dart`, over `FakePlanningSource`, in seconds):

- Red first: a save whose source throws `TimedOut` and whose next streamed board shows the moves leaves no move
  pending, no `moveProblem`, and `moveNotice` saying it saved. Harm: the person, told the save failed and the moves
  were made elsewhere, takes a pushed change for a lost one and makes it again or undoes it by hand.
- A save that throws `TimedOut` and whose streamed board does not show the moves keeps them pending and Save
  offered, and a second save, sent after the slow one landed in the fake's board, is refused `StaleRevision` by the
  fake, with the store written once. Harm: a move written twice.
- The joining of messages gets no test: it only reads wrong.

## Acceptance

- `flutter test` in `screen/` passes with both tests, the first seen red on today's `PlanningBoard.save`.
- A save answered late shows as saving, then saved; no message says the planner did not answer, and no message runs
  into another.

## To be checked

- Whether a second save sent while the first is still pushing waits for the store's lock and is then refused
  `StaleRevision`, or is refused another way, read in `GitStore` and `BoardController`.
- That the planner finishes a save whose request the screen aborted, rather than stopping it on the closed connection.
