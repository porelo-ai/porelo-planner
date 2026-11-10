# PL21 — A Streamed Board Kept Over A Slower Read

```json
{
  "$schema": "issue-1.json",
  "id": "PL21",
  "title": "A Streamed Board Kept Over A Slower Read",
  "state": "Implementing",
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

- **`PlanningBoard`** (`screen/lib/src/planning_board.dart`): a reply's number is taken when its read starts, not when
  it answers. `_read` numbers its reply before it reads; `save` numbers the saved board before `saveMoves` and the
  board read again after it before `stories`. A streamed board that arrives during a reload or a save is deferred
  with its number as today and shown once the call ends, unless a read started after it has been shown.
- **Red first** (`screen/test/planning_moves_test.dart`, unit tests over the fake source): a reload whose
  `stories` answers r2 after the stream told r3 keeps r3; a save whose read after it answers an older board than
  the one streamed during it keeps the streamed one.

## Acceptance

- Both tests seen red against today's numbering, then green; `./mvnw -B -s settings.xml -Pall -pl screen verify`
  green.
