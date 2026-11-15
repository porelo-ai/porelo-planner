# PL152 — The Issue Page Names A Task Where It Ran

```json
{
  "$schema": "issue-1.json",
  "id": "PL152",
  "title": "The Issue Page Names A Task Where It Ran",
  "state": "Implementing",
  "size": "S",
  "priority": "High",
  "rank": 72,
  "blockedBy": [],
  "covers": "The issue page's Work part lists each task of the story with the machine it ran on, as the planner reports it, and whether it ended, and asks the window to open only a task known where it runs"
}
```

**Why this size.** S - the screen package's issue page and task link, with their tests; no contract.

**What must be true.** A person who reads an issue's Work part sees where each task ran and that an ended task ended,
instead of a task called unknown on this machine.

## Plan

- Seen in a live run: the side panel read "Work: Nobody at work on this story · pi-2 · This window does not know task
  pi-2 on this machine", while pi-2 had run and ended on the rented host. The Work part
  (`screen/lib/src/ui/planning_issue_view.dart`) draws each task of the story's work as a `PlanningTaskLink` with the
  name alone and the window's answer whether it can open it; the planner already reports each task's `machine` and
  `ended`.
- Each task's line says its name, `on <machine>` as reported, and `ended` with its end's time in the person's zone when
  it ended; the window is asked to open it only while it is at work, and an ended task's line opens nothing.
- The window's own words for a task it cannot open are the window's; this screen no longer shows them for an ended
  task.
- Red first, in the screen's issue page tests over the fake board: an ended task on another machine reads with that
  machine and as ended, and no unavailable words. Harm: a person sent to look for a task on the wrong machine.

## Acceptance

- The test above, seen red against today's page; the screen's suite passes.
