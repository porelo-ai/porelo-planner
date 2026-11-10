# PL21 — A Streamed Board Kept Over A Slower Read

```json
{
  "$schema": "issue-1.json",
  "id": "PL21",
  "title": "A Streamed Board Kept Over A Slower Read",
  "state": "Backlog",
  "size": "-",
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
