# PL49 — Work Moves On By Itself From A Move On The Board

```json
{
  "$schema": "issue-1.json",
  "id": "PL49",
  "title": "Work Moves On By Itself From A Move On The Board",
  "state": "Analyzed",
  "size": "L",
  "priority": "High",
  "rank": 24,
  "blockedBy": [
    "PL46"
  ],
  "covers": "Moving an issue or a story into a working state on the board opens the Start dialog filled with the project's defaults, one tab per task, and the chain the work rules give - build, reading, rework, review - runs on by itself within a reading limit the project's work rules set; the model and effort follow the task's phase and the issue's risk",
  "openQuestions": 0
}
```

**Size:** L - a move that starts work crosses the store, the core's work run, the board's contract and the screen;
beside it two smaller changes to the work rules.

**What must be true.** A person moves an issue to Implementing on the board, and the planner does the rest as the
project's work rules say: the builder starts with that state's instructions; work that ends done goes to Reading and
a reader starts; a reading with blocking findings goes to Reworking and the builder starts with the findings; a
reading without goes to Review, and only there is the person asked. A reading beyond the project's limit is never
started.

## Plan

What stands today, read in the code:

- `stories/work.yml` in the project gives each working state its `from`, `done`, `then`, `blocked`, `per`, `modes`,
  `order` and `models`; `WorkRules` keeps the sound ones.
- A person starts a story's work with Start (`StoryWorkRequest`, `WorkRun`); the window starts the tasks. A task's
  end is reported (`TaskEventRequest`), `WorkEnds` judges it, `NextStep` plans the next state and tasks along
  `then` and `blocked`, `ReadingCycle` counts readings against the limit and stops in Reading under Needs you on
  the last one; the screen starts the next tasks at once (`task_runs.dart`, `follow`).
- So the chain from building through reading and rework to Review runs by itself already, once started from a
  story's Start. What is missing:

1. **A move opens the Start** (L). A move of an issue into a working state opens the Start dialog filled with
   the project's defaults - the first mode, the first agent and its model, the issue's own repository, the limit
   of step 2 - and the person confirms or changes them before anything starts; cancelling the dialog cancels the
   move. A story moved into a working state moves its least advanced issues along (as `Moves.check` does) and
   starts all their tasks: the dialog shows one tab per task, each filled the same way. The planner answers the
   planned tasks for a move before it is saved, so the dialog shows what the work rules give; saving the move and
   the start is one request. A Manual issue gets no tab ([PL46](README.md#pl46)). A move out of a working state
   while its task runs asks the person to stop the task or cancel the move. A change to the window's contract.
   Red first in `core`: a planned move of an Agent issue from Backlog to Implementing yields one building task for
   its repository; a story's move one per Agent issue; a Manual issue's none.
2. **The reading limit in the work rules** (S). The Reading rule takes `readings: <n>`, one to ten; a start without
   its own limit takes it, and `WorkRules` refuses a rule without one where a `blocked` state leads back to it. Red
   first: a Reading rule of two readings stops the third in Reading under Needs you.
3. **Model and effort by phase and risk** (M). An issue's head may carry `risk`: `harm`, `ordinary` (when none is
   set) or `small`. The work rules give per working state the effort for each risk, and a start hands agent, model
   and effort to the window and names where the choice came from. The effort's meaning per agent is Porelo's.
   Red first: a Reading task for a `harm` issue carries the rule's effort for `harm`.

Order: 2, 1, 3. Sum by the medians: 1 L, 1 M, 1 S = 113 + 67 + 33 = 213 minutes.

## Acceptance

- With the work rules of Implementing to Reading, Reading to Reworking or Review, and two readings: an Agent issue
  moved to Implementing gets its builder started; an end done sets Reading and starts the reader; a reading with
  blocking findings sets Reworking and starts the builder with them; the next reading without sets Review; a third
  reading is never started. A Manual issue moved the same way gets no task.

Work that moves on while no window is open is a task of its own ([PL51](README.md#pl51)); here the chain goes on
from the window as it does today.

## To be checked

- What the window's Start takes today for an effort, and whether Porelo maps one effort scale per agent.
