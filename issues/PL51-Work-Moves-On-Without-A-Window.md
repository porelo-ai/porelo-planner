# PL51 — Work Moves On Without A Window

```json
{
  "$schema": "issue-1.json",
  "id": "PL51",
  "title": "Work Moves On Without A Window",
  "state": "Idea",
  "size": "L",
  "priority": "High",
  "rank": 25,
  "blockedBy": [
    "PL49"
  ],
  "covers": "The chain the work rules give - build, reading, rework, review - goes on while no window is open: the planner learns a task's end and starts the next task itself"
}
```

**Size:** L - crosses repositories: the planner needs Porelo to start a task and to tell its end without the window.

**What must be true.** Once work has started, a task that ends starts the next one the work rules give - a reader
after a build, the builder after a reading with blocking findings - while no window is open, and the board shows
where it stands when the window opens again.

## Plan

- Today the window starts each task with Porelo's own Start and reports its end to the planner
  (`TaskEventRequest`); the planner plans the next step (`NextStep`) and the window starts it (`task_runs.dart`,
  `follow`). With the window closed nothing reports an end and nothing starts the next task.
- The planner learns a task's end from Porelo and starts the next task through Porelo itself, with the values the
  person confirmed at the start; the window only shows it. This needs a contract with Porelo for starting a task
  and for its end, agreed in the channel.

## Acceptance

- With the window closed after a build starts, the build's end starts the reader, and a reading with blocking
  findings starts the builder, up to the reading limit; opening the window shows the issue where the chain left it.

## To be checked

- How Porelo can start a task and tell its end to a program other than the window, and with whose rights.
