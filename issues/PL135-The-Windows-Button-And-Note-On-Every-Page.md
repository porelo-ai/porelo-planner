# PL135 — The Window's Button And Note On Every Page

```json
{
  "$schema": "issue-1.json",
  "id": "PL135",
  "title": "The Window's Button And Note On Every Page",
  "state": "Implementing",
  "size": "XS",
  "priority": "Highest",
  "rank": 9,
  "blockedBy": [],
  "covers": "The window's own page button also stands beside the crumb of the page shown when no planning can be read, and an optional page note of the window stands under the board's head row and under a story's or an issue's breadcrumb, so the window draws no head of its own above the screen"
}
```

**Size:** XS - two optional parameters of the screen widget and one test; no contract with the planner.

**What must be true.** The window's help button is beside the title on every planning page, and the window's
first-time words stand under that title, without a head of the window's own above the screen.

## Plan

- `screen/lib/src/ui/planning_view.dart`:
  - The page shown when no planning can be read puts `pageButton` in a row beside its "Planning" crumb.
  - `PlanningScreen` takes an optional `pageNote` (null by default) and shows it under the board's head row and
    under a story's or an issue's breadcrumb.
- Red first: a screen test in `screen/test/planning_live_state_test.dart` that the button stands beside the crumb of
  the page without planning, and that the note stands on the board and on a story's and an issue's page.

## Acceptance

- The screen's test passes and was seen red against today's screen.
