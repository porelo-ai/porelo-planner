# PL128 — One Reload Planning Button Whatever The State

```json
{
  "$schema": "issue-1.json",
  "id": "PL128",
  "title": "One Reload Planning Button Whatever The State",
  "state": "Implementing",
  "size": "S",
  "priority": "Highest",
  "rank": 2,
  "blockedBy": [],
  "covers": "The planning screen has one button, Reload planning, which in one press fetches the repositories and reads the board anew, re-opening the planner's stream first when it is down or the board has a problem, instead of a Retry planning and a Reload planning the person cannot tell apart"
}
```

**Size:** S - the screen package's board and view and their tests; no contract another repository reads.

**What must be true.** A person who sees planning stale, down or faulty presses one button, Reload planning, and gets
the board the repositories hold now, whatever was wrong.

## Plan

- Today `screen/lib/src/ui/planning_view.dart` shows *Retry planning* (`board.retry()`, which re-opens the planner's
  stream through `changeProject` and fetches nothing) when the board has a problem, beside *Reload planning*
  (`board.reload(fetch: true)`, which fetches and reads the board but leaves a down stream down); the story page shows
  *Retry planning* alone.
- `PlanningBoard` (`screen/lib/src/planning_board.dart`) gains `reloadPlanning()`: while the stream is live and the
  board has no problem, it is `reload(fetch: true)`; otherwise it asks the planner to fetch, saying when it cannot,
  and then re-opens the stream, whose first answer is the board read anew. `retry()` stays for callers that restart
  the watch alone.
- Both places in the view show one *Reload planning* (key `planning-reload`) that calls it; *Retry planning* and its
  key go.
- Red first, in `planning_board_test.dart`: a board whose stream ended is live again after `reloadPlanning()`, and
  the planner was asked to fetch; the label itself gets no test.

## Acceptance

- The unit test above, seen red before the change; the screen's suite passes.
