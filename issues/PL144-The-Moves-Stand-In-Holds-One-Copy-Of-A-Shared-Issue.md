# PL144 — The Moves Stand-In Holds One Copy Of A Shared Issue

```json
{
  "$schema": "issue-1.json",
  "id": "PL144",
  "title": "The Moves Stand-In Holds One Copy Of A Shared Issue",
  "state": "Backlog",
  "size": "S",
  "priority": "Low",
  "rank": 64,
  "blockedBy": [],
  "covers": "The screen package's stand-in for the planner's moves treats an issue two stories share as one issue, so a move, its preview and the checks after the batch read and write the same state, as the planner's moves do"
}
```

**Why this size.** S - one Dart file of the screen package and its tests; no contract.

**What must be true.** A window test that moves stories sharing an issue sees the same issues moved, previewed and
refused as the planner answers.

## Plan

- Today `applyPlanningMove` and `previewPlanningMove` (`screen/lib/src/fake/planning_moves.dart`) read the board's
  stories, each holding its own decoded map of every issue. An issue two stories share is two maps: a story's move
  writes only its own story's copy, an issue's own move writes the first copy found, and the preview's `issuesAfter`
  takes the last copy. So a story finished over an issue it shares with another story is previewed with the issue in
  its old state, and an issue's own move that reopens the shared issue is not seen by the other story's check after
  the batch.
- The stand-in builds one map per issue id before the batch and has every story's list point at it, so each write is
  seen everywhere; the preview reads from the same maps.
- Red first, in `screen/test/planning_moves_test.dart`: two stories sharing an issue in Review; finishing one previews
  the shared issue as moved to Finished; the batch of the other story's move, the finish and the shared issue's own move
  back to Ready is refused as the planner refuses it. Harm: a window test that passes against the stand-in for a batch
  the planner refuses.

## Acceptance

- The tests above, seen red against today's stand-in; the screen's suite passes.
