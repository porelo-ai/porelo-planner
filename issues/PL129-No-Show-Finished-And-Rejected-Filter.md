# PL129 — No Show Finished And Rejected Filter

```json
{
  "$schema": "issue-1.json",
  "id": "PL129",
  "title": "No Show Finished And Rejected Filter",
  "state": "Implementing",
  "size": "S",
  "priority": "Highest",
  "rank": 3,
  "blockedBy": [],
  "covers": "The planning screen has no Show Finished and Rejected filter: the closed states always stand in their phase, which folds like any other, and the filter's preference and count go"
}
```

**Size:** S - the screen package's board and view, their tests and the changelog; no contract of the planner.

**What must be true.** The person folds and opens the Done phase as any other to see or hide finished and rejected
stories, with no separate filter for it.

## Plan

- Today `screen/lib/src/ui/planning_view.dart` shows the chip *Show Finished and Rejected (n)* (key
  `planning-show-closed`), bound to `PlanningPreferences.showClosed`, `PlanningBoard.toggleClosed()` and
  `PlanningBoard.closedCount`; `visible()` hides every closed state while it is off. The closed stories come with the
  board like every other; no read of its own serves them.
- The chip, `showClosed`, `toggleClosed()`, `closedCount` and `visible()` go; `statesIn` lists every state of a phase.
  The Done phase stays folded at first, as today, so a first open still shows the open work whole.
- No red test: the removal guards no harm. The tests that read the chip or the count go with it.
- The window's own tests that tap `planning-show-closed` or read its label change with the window's next take of the
  screen; that is said to the window's agent before the hand-over.

## Acceptance

- The screen's suite passes without the chip; a board shows its closed states inside the Done phase.
