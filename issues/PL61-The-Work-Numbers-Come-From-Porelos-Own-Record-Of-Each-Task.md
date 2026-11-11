# PL61 — The Work Numbers Come From Porelo's Own Record Of Each Task

```json
{
  "$schema": "issue-1.json",
  "id": "PL61",
  "title": "The Work Numbers Come From Porelo's Own Record Of Each Task",
  "state": "Idea",
  "size": "-",
  "priority": "High",
  "rank": 1,
  "blockedBy": [],
  "covers": "The project's velocity and an issue's stages are read from the record Porelo keeps of each task, its start, end and tokens, and an agent's own stage line counts only until that record exists"
}
```

**What must be true.** The time and tokens an issue's work cost are those Porelo itself measured for the tasks that
worked it, never what an agent wrote about itself, and the velocity an estimate is given comes from them.

## Plan

- A task works one issue in one phase, so a task's record is one stage: its stage is the phase, its minutes the
  task's start to end, its tokens and model those Porelo counted. A person's move on the board stays a record of its
  own, written by the planner.
- The planner starts each task with the issue it works and its phase: the working state of the project's workflow the
  task works the issue in, such as Implementing, Reading or Reworking, as the work rules name it.
- The planner reads a project's task records, removed tasks among them, through Porelo's own interface (a call on the
  daemon or its command line, never its files), and answers the velocity from them: a stage's minutes are the task's
  wall time, its start to its end, as the stage lines count today. Until then it takes the stage lines agents write,
  in the format they write today.
- Red first: a task record of two hours on an issue of size M gives a velocity of 120 minutes for M, and an agent's
  own line for the same issue and phase is not counted twice.
