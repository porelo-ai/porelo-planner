# PL64 — A Failed Task Or A Blocking Reading Asks The Person

```json
{
  "$schema": "issue-1.json",
  "id": "PL64",
  "title": "A Failed Task Or A Blocking Reading Asks The Person",
  "state": "Implementing",
  "size": "L",
  "priority": "High",
  "rank": 1,
  "blockedBy": [],
  "covers": "A task that fails keeps its card in its working state with a warning and a Needs-you entry to its result, a blocking reading asks for Reworking through a Needs-you entry, and a task that starts by itself takes the first mode and model without a dialog"
}
```

**Size:** L - three changes across the core's next step, one contract change (a new reason and the Rework choice's
mode and model), the app and the screen package's card and Needs-you row; the window draws the row unchanged.

**Estimate:** 113 min, the measured median of L. Uncertain: whether the window still lists a task after it ended, so
the row reaches its result, is read in the window's code only; where it does not, the row's fallback grows.

**What must be true.** A person sees at once where the work stopped and needs them, and gets there in one step; the
work goes on by itself only where the project's work rules say so.

- **A task that fails** leaves its card in the working state it was started for, marked with a warning, and adds a
  Needs-you entry that opens the failed task's result directly. This holds for every working state of the project's
  workflow. A task that finished but left commits unpushed, and a reader whose answer held no findings block, count
  as failed: the same warning mark and a row to its result.
- **A reading that blocks** adds a Needs-you entry that opens the Reworking dialog directly. Reworking does not start
  by itself, and the work rules set no cap on reading rounds; the dialog's reading rounds stay as they are. The
  dialog keeps all three choices a stopped reading offers today: *Rework*, *Review anyway* and *One more reading*.
- **A task that starts by itself** through a rule's `then:` takes the rule's first mode and first model, with no
  dialog. A move by hand opens the start dialog with the first mode and model preselected, and the person may change
  them.

## Plan

Read in today's code:

- The window reports Porelo's end record of each task it started (`finished`, `why`, `answer`, `unpushed`) as an
  `ENDED` task event; `WorkEnds.Outcome.of` makes a run that did not finish `FAILED`, `WorkEnds.judge` a
  `NeedsOperator(FAILED)`, and `NextStep.after` records `Progress.attention("FAILED")` and writes nothing. So the issue
  already stays in its working state, raised with reason `FAILED`, in every working state. What is missing is on the
  screen: the card shows no warning (`planning_view.dart` marks only a stopped reading), and the Needs-you row
  (`StoryWorkAttentionView`) offers only *Open story*, a dialog of the story's text and the end's words.
- The window lists the screen package's `PlanningBoards.needsYou` in its *Needs you* and draws each row with the
  package's `StoryWorkAttentionView`; a row carries `PlanningAttention` (board, story, issue, end, reason). The task's
  name is in `PlanWork.tasks[].task`, and `PlanningTaskChanges.open(board, task)` already opens a task in the window.
- A blocking reading goes on by itself today: `ReadingCycle.count` answers `REWORK` while readings are left, and
  `NextStep.after` moves the issue to the rule's `blocked` state and offers the builder at once; only the last allowed
  reading stops in Reading (`ROUND_LIMIT`).
- A task that starts by itself takes the first agent's model already, but the mode of the task that ended
  (`NextStep.task` is given `binding.mode()`); where the next rule does not offer that mode the offer is refused and
  the issue raised `NEXT_STEP_UNAVAILABLE`. With today's work rules a build in `AGENT` mode never gets its reader,
  whose rule offers only `UNATTENDED`.
- The start dialog a move by hand opens (`StoryWork.load`) already preselects the first mode the proposal offers and
  the first agent's model; nothing changes there.

Steps:

1. **First mode by itself** (`core`). `NextStep.task` takes the first mode of the rule it starts, not the ended task's
   mode. Red first, unit, in `NextStepTest`: a build in `AGENT` mode ending done offers the reader in `UNATTENDED`
   with the Reading rule's first model.
2. **A blocking reading stops** (`core`, contract, `app`). `ReadingCycle.count` stops on every reading with blocking
   findings: the issue stays in Reading, `readingStopped` true, reason `BLOCKING_FINDINGS` below the readings granted
   and `ROUND_LIMIT` at them; nothing is offered. The Rework choice of a stopped reading (`ReadingRequest`) takes
   `mode`, `agent` and `model`, and needs no words of the person when blocking findings go with it; the offered task
   carries the findings as today. `planner.yaml` gets the reason and the request's fields; the mock mode answers
   them. Red first, unit, in `ReadingCycleTest`: a blocking reading on the first of two readings answers `STOP`; in
   `NextStepTest`: it offers no task and writes no state; integration in `app` (`WorkRunHttpTest`): a Rework choice
   naming a mode the Reworking rule does not offer is refused.
3. **The card and the row** (`screen`). A card with an issue raised in a working state shows a warning mark, a button
   to *Needs you* as a stopped reading has today. A row whose reason is `FAILED`, `UNPUSHED` or `INVALID_FINDINGS`
   (each already raised with its own reason by `WorkEnds.judge`) opens the task's result directly through
   `PlanningTaskChanges.open`, and says the window's reason where it cannot; a row whose reason is
   `BLOCKING_FINDINGS` or `ROUND_LIMIT` opens the Reworking dialog directly: the blocking findings, the Reworking
   rule's modes and agents with the first preselected, words optional, and beside *Rework* the choices *Review
   anyway* and *One more reading* as a stopped reading offers them today. Red first, widget tests of the screen
   package: a `FAILED` row's action calls `open` with the failed task's name, and an `UNPUSHED` row's does the same;
   a `BLOCKING_FINDINGS` row's dialog sends a Rework choice with the first mode and model.

Order: 1, 2, 3. Every test runs over fakes in seconds; no VM.

## Acceptance

- With today's work rules: a build in `AGENT` mode that ends done gets its reader in `UNATTENDED`; a reading with
  blocking findings leaves the issue in Reading with no task and one Needs-you row, whose dialog starts the rework
  with the first mode and model preselected; a task that did not finish leaves its card in its working state with a
  warning, and its row opens that task in the window, as does the row of a task that left commits unpushed and of a
  reader whose answer held no findings block; the Reworking dialog offers *Rework*, *Review anyway* and *One more
  reading*. Seen failing first: today's reader offer refused for its mode, and today's rework offered at once.

## To be checked

- Whether the window still lists a task after it ended, so `open` reaches its result, and what `unavailable` says
  where it does not.
- Whether a board whose planning view was never shown has its `taskChanges` set, since the Needs-you row may be the
  first thing a person sees of it.
