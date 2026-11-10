# PL21 — A Streamed Board Kept Over A Slower Read

```json
{
  "$schema": "issue-1.json",
  "id": "PL21",
  "title": "A Streamed Board Kept Over A Slower Read",
  "state": "Review",
  "size": "S",
  "priority": "Medium",
  "rank": 11,
  "blockedBy": ["PL17"],
  "covers": "A board streamed while a reload or a save is reading is never replaced by the older board that read answers"
}
```

**What must be true.** When a newer board arrives on the stream while the planning board reloads or saves, the
screen keeps the newer one: the board read by the slower call, older than the streamed one, never replaces it.
Today `PlanningBoard` (`screen/lib/src/planning_board.dart`) numbers a reply when it arrives, so a read that started
before a streamed board but answered after it gets the higher number and the streamed board is dropped.

**Why this size.** One class of the screen package and its tests, no contract another repository reads.

## Plan

- **`PlanningBoard`** (`screen/lib/src/planning_board.dart`): a read is numbered when it starts, not when it
  answers; the stream tells every revision in order and never an older one after a newer one, so a board streamed
  while a read waits wins over that read's answer, and a newer answer is told next. The saved board is numbered after
  `saveMoves`, since a board streamed while the save waits was read before the save was made. After a reload, a stale
  save or the read after a save, only the newer of the read's board and the deferred streamed board is shown, so
  pending moves and the notice are worked out against the board that stays. A deferred board is replaced only by one
  numbered higher, since a board that reads its workflow first may come after a newer one.
- **Red first** (`screen/test/planning_moves_test.dart`, unit tests over the fake source): a board streamed while a
  reload, the read after a stale save or the read after a save waits wins over what that read answers; a board
  streamed while a save waits loses to the saved board, whether the read after it answers or fails; one streamed
  while a stale save waits loses to the read after the refusal; an earlier streamed board still reading its workflow
  never replaces a later one; pending moves are reconciled, and a stale save's notice given, against the board that
  stays. The old test that a read in flight wins over a board streamed meanwhile is turned around.
- **The code reading's findings**, each fixed with a test seen red first: the saved board was numbered before the
  save; a deferred board was replaced by an earlier one; the discarded read's board reconciled pending moves first. A
  check in `_finishOperation` that `_accept` already makes is removed. Declined: a read numbered before its workflow
  read, or a stream event already queued when a read starts, can show an older board for a moment, which the stream
  replaces with the next revision it tells; a mock source's stories-only stream keeps the workflow it had, which the
  mock never changes; a refusal's words replaced by the reconciliation of a move made elsewhere state what is now
  true.

## Acceptance

- Both tests seen red against today's numbering, then green; `./mvnw -B -s settings.xml -Pall -pl screen verify`
  green.
