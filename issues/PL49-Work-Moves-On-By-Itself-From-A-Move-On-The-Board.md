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
    "PL64",
    "PL65"
  ],
  "covers": "Moving an issue or a story into a working state on the board opens the Start dialog filled with the project's defaults, one tab per task, and the chain the work rules give - build, reading, review - runs on by itself, asking the person where a reading blocks; a reading can be started by hand; the model and effort follow the task's phase and the issue's risk",
  "openQuestions": 1
}
```

**Size:** L - a move that starts work crosses the core's proposal, the board's contract and the screen; beside it a
reading started by hand, and model and effort chosen by phase and risk.

**What must be true.** A person moves an Agent issue, or a story, into a working state on the board, and the planner
does the rest as the project's work rules say: the start dialog opens filled with the rule's first mode and the first
agent's model, the builder starts with that state's instructions; work that ends done goes to Reading and a reader
starts by itself; a reading without blocking findings goes to Review, and a reading with them asks the person.

## Plan

What stands today, read in the code:

- `stories/work.yml` in the project gives each working state its `from`, `done`, `then`, `blocked`, `per`, `modes`,
  `order` and `models`; `WorkRules` keeps the sound ones. The project's rules now include In analysis (from Idea and
  Accepted), Under estimation (from Analyzed) and Reading from Review too.
- A story dropped on a working state already opens the start dialog (`planning_view.dart`, `_start` with the target),
  which preselects the proposal's first mode and the first agent's model. But the proposal (`WorkProposal.propose`)
  names every open issue of the story, whatever its state, and the start (`NextStep.started`) refuses an issue whose
  state the rule's `from` does not hold, so a story whose issues stand in different states cannot be started.
- An issue cannot be moved into a working state: the screen stages it as a move, and `Moves.check` refuses it
  (`EnteredOnlyByWork`). The proposal and the start take a whole story, never chosen issues.
- A Reading cannot be started by hand: `WorkProposal.propose` refuses every rule with `blocked` ("Readings are offered
  only by a run"), though the Reading rule now enters from Review.
- Once started, the chain runs by itself: a task's end is reported (`TaskEventRequest`), `WorkEnds` judges it,
  `NextStep` plans the next state and tasks along `then`, and the screen starts them at once (`task_runs.dart`,
  `follow`). How a blocking reading and a task's mode are handled changes with
  [PL64](README.md#pl64): a blocking reading asks the person instead of starting Reworking, and a task that starts by
  itself takes the rule's first mode. A Manual issue gets no task in any start ([PL65](README.md#pl65), on the worker
  field of [PL46](README.md#pl46)).

What is missing:

1. **A move opens the Start for what it moves** (M). The proposal and the start take an optional list of the story's
   issues; a move of an issue into a working state opens the start dialog for that issue alone, and cancelling the
   dialog cancels the move. A story's proposal names its least advanced open issues whose state the rule's `from`
   holds, as `Moves.check` moves a story's least advanced issues, one tab per task. A change to the window's
   contract (`StoryWorkProposalRequest`, `StoryWorkRequest`), the mock mode's answer and the screen's issue move
   action. Red first, unit, in `WorkProposalTest`: a story with an issue in Backlog and one in Review, proposed for
   Implementing, names only the Backlog issue; a proposal for one named issue names it alone.
2. **A reading started by hand** (S). A proposal for a rule with `blocked` offers one reader per issue, each issue
   alone, with the readings counted on from the issue's run. Red first, unit, in `WorkProposalTest`: a Reading for an
   issue in Review offers one reader for it; in `NextStepTest`: its clean end leads to Review.
3. **Model and effort by phase and risk** (M). An issue's head may carry `risk`: `harm`, `ordinary` (when none is
   set) or `small`. The work rules give per working state the effort for each risk, and a start hands agent, model
   and effort to the window and names where the choice came from. The effort's meaning per agent is Porelo's. Writing
   `risk` into a head waits until the build tools' issue schema takes it, as the worker field does. Red first, unit:
   a Reading task for a `harm` issue carries the rule's effort for `harm`.

Not part of this issue: a reading limit in the work rules, since the work rules set no cap on reading
rounds for now and the start dialog's reading rounds stay as they are; and asking to stop a running task before a
move out of a working state, which belongs to the warnings before a move ([PL38](README.md#pl38)).

Order: 2, 1, 3. Sum by the medians: 2 M, 1 S = 67 + 67 + 33 = 167 minutes, an L.

## Acceptance

- With today's work rules: an Agent issue moved to Implementing opens the start dialog for it alone, with the first
  mode and model preselected, and its builder starts on confirmation; an end done sets Reading and starts the reader
  in its rule's first mode; a reading without blocking findings sets Review; one with blocking findings stays in
  Reading and asks the person. A story with issues in Backlog and Review dropped on Implementing starts only the
  Backlog issue. An issue in Review can be read again by hand. A Manual issue moved the same way gets no task.

Work moves on only while a window is open: the window, not the planner, starts every task with Porelo's `Start`, and
reports each end. When no window was open as a task ended, the board is read again and the next step taken once a
window starts again; the planner as a service of the system does not change that, since it starts no task itself.

## Open questions

- Does model and effort by phase and risk stay in this issue, or become an issue of its own, since it needs `risk` in
  the issue's head, which the build tools' schema must take first?

## To be checked

- What the window's Start takes today for an effort, and whether Porelo maps one effort scale per agent.
