# PL161 — The Boards Take the Window's Stop Hook at Their Start

```json
{
  "$schema": "issue-1.json",
  "id": "PL161",
  "title": "The Boards Take the Window's Stop Hook at Their Start",
  "state": "Implementing",
  "size": "XS",
  "priority": "High",
  "rank": 2,
  "blockedBy": [],
  "covers": "PlanningBoards takes the window's task changes when it is made, as it takes the start hooks, and hands them to every board it makes, so Stop work on a Needs you row is offered before the planning page was ever shown"
}
```

**Why this size.** XS: one optional parameter of `PlanningBoards`, and one line where a board is made. One test.

**What must be true.** A person who sees a running or raised story in *Needs you* can stop its work there, whether or
not they opened the planning page first.

## Found

On 2026-11-17, in a live test on a rented host. A ST372 row in *Needs you* showed *Stop work* disabled, with the
reason "the window has not handed the planning screen the hook that stops tasks". The planning page had not been
shown since the window started.

## Plan

- Today: `PlanningBoard.taskChanges` (`screen/lib/src/planning_board.dart`) is set only by `PlanningView.build`
  (`screen/lib/src/ui/planning_view.dart:127`). `stopUnavailable` refuses while it is null (line 502). The start
  hooks, in contrast, reach every board through the `PlanningBoards` constructor (`startTask`, `agents`, `wizards`),
  which sets `board.startHooks` where a board is made.
- Change: `PlanningBoards` takes an optional `PlanningTaskChanges? taskChanges`, keeps it, and sets
  `board.taskChanges` beside `board.startHooks` where a board is made. `PlanningView` still sets it as today.
- Red first: a `PlanningBoards` made with task changes yields a board whose `stopUnavailable` for open work is null,
  without a `PlanningView` ever being built.

## Acceptance

- With the window handing its task changes to `PlanningBoards`, Stop work is offered on a *Needs you* row before the
  planning page was shown.
- The test above fails without the change.
