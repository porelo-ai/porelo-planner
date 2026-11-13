# PL43 — A Slow Save Told As Slow, Not As A Failure

```json
{
  "$schema": "issue-1.json",
  "id": "PL43",
  "title": "A Slow Save Told As Slow, Not As A Failure",
  "state": "Review",
  "size": "S",
  "priority": "Highest",
  "rank": 4,
  "blockedBy": [],
  "covers": "A save that takes longer than the screen waits is shown as still saving and, once the board shows it done, as saved; two messages never run together"
}
```

**Size:** S - the screen package alone (`PlanningBoard`, `HttpPlanningSource._call` and their tests); the planner and the contract stay as they are.

**Estimate:** 33 min, the measured median of S. Uncertain: if the move flow is first rewritten to one save per move
([PL38](README.md#pl38), ranked before this one), the same rules land in that flow's save instead of the batch's.

**What must be true.** A person who moves a story or an issue sees it being saved while the push takes its time, and
sees it saved once the board shows the move; a slow push is never told as a failure, and each message stands apart.

## Plan

Today, read in `screen/lib/src`:

- `HttpPlanningSource._call` (`http/planner_source.dart`) waits `timeout`, 30 s, aborts the request and throws
  `PlanningRefusal('TimedOut', ..., 'the planner did not answer within 30 s')`. The name `TimedOut` is only this
  deadline of the screen's own: the planner's own store timeout arrives as `Unreachable` (`Problems`, HTTP 504).
- The planner goes on with the save after the screen stops waiting. The move request runs on a servlet thread
  (`spring-boot-starter-web`), and a connection the screen closes interrupts nothing: the handler finishes and only
  the answer's write fails. `BoardController.saveMoves` runs inside `WorkRun.moves`, which holds the project's work
  record lock (`WorkRecord.locked`, waited on without a bound), then `GitStore` waits up to 30 s for the store's lock
  (`LOCK_WAIT`) and commits in the planner's own clones; the save answers once those commits stand, and `LocalSaves`
  pushes them in the background. What makes the screen wait is the two locks and the local commit, not the push.
  `saveMoves` calls `changes.saved(project)` in `finally`, so the stream sends the board afterwards.
- A second save sent while the first still runs waits for the work record lock behind it; `BoardController.saveMoves`
  then compares the revision it carries with the store's board and refuses it as `StaleRevision` before the store is
  asked, and `GitStore` checks the revision once more under its own lock. The store is written once.
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
   carries the revision of the board shown, and the planner refuses it as `StaleRevision` if the slow save landed after
   that board, so it is never written twice.
3. `unanswered` moves wait at most `unansweredWait`, 5 min by default, past the planner's own bounds on a save (30 s
   for the store's lock and a few Git calls of 60 s each). Past it, each is no longer `unanswered`, stays pending
   with a problem, and `moveProblem` says the save was not seen on the board and to save again. A later save refused
   other than `StaleRevision`, or failing, clears `unanswered` on its moves, so a board showing them afterwards tells
   a move made elsewhere.
4. In `HttpPlanningSource._call`, a timeout while the connection is still being opened is `PlannerUnreachable`:
   nothing was sent, so nothing can still be saving. Only a request sent and not answered is `TimedOut`.
5. `moveProblem` joins its parts with a line break instead of a space, in `_accept` and in the reread after
   `PartlySaved` or `StaleRevision`; the views' `Text` shows each on its own line.

Tests, unit level (`flutter test`, `test/planning_moves_test.dart`, over `FakePlanningSource`, in seconds):

- Red first: a save whose source throws `TimedOut` and whose next streamed board shows the moves leaves no move
  pending, no `moveProblem`, and `moveNotice` saying it saved. Harm: the person, told the save failed and the moves
  were made elsewhere, takes a pushed change for a lost one and makes it again or undoes it by hand.
- A save that throws `TimedOut` and whose streamed board does not show the moves keeps them pending and Save
  offered, and a second save, sent after the slow one landed in the fake's board, is refused `StaleRevision` by the
  fake, with the store written once. Harm: a move written twice.
- Unanswered moves the board never shows turn into a problem after the deadline and stay pending. Harm: a save that
  never lands is told as still saving, and the person leaves a lost change behind.
- A planner that takes no connection in time is refused as `PlannerUnreachable` (`planner_source_test.dart`, over a
  loopback listener that never accepts). Harm: a save that never left the window is told as still saving.
- The joining of messages and the clearing of `unanswered` after a refusal get no test: they only read wrong.

## Acceptance

- `flutter test` in `screen/` passes with both tests, the first seen red on today's `PlanningBoard.save`.
- A save answered late shows as saving, then saved; no message says the planner did not answer, and no message runs
  into another.
