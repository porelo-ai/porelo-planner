# PL64 — A Failed Task Or A Blocking Reading Asks The Person

```json
{
  "$schema": "issue-1.json",
  "id": "PL64",
  "title": "A Failed Task Or A Blocking Reading Asks The Person",
  "state": "Review",
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

1. **First mode by itself** (`core`). `NextStep.task` takes `NextStep.Settings`, null for a task the planner starts
   by itself, which then runs in the first mode and first model of the rule it starts, never the ended task's mode.
   *One more reading* takes the Reading rule's first ones the same way. Red first, unit, in `NextStepTest`: a build in
   `AGENT` mode ending done offers the reader in the Reading rule's first mode and model.
2. **A blocking reading stops** (`core`, contract, `app`). `ReadingCycle.count` stops on every reading with blocking
   findings: the issue stays in Reading, `readingStopped` true, reason `BLOCKING_FINDINGS` below the readings granted
   and `ROUND_LIMIT` at them (`ReadingCycle.stopReason`); nothing is offered and no state is written, so a parent no
   longer defers on a blocking reading of its own: it waits on the person, not on its children.
   - Rounds still count: a *Rework* below the limit leaves the rounds as they are (one more is granted only at the
     limit, as today), and the next blocking reading stops again, `BLOCKING_FINDINGS` or `ROUND_LIMIT` at the limit.
   - `ReadingCycle.choose` takes the stopped reading's saved findings: a *Rework* needs no words when a saved finding
     blocks, decided in core from the record, never from the request.
   - The Rework choice (`WorkRun.choose`) takes optional `NextStep.Settings`; `NextStep.Settings.of` checks in core
     that the mode is one of the Reworking rule's modes and the agent one of its agents with exactly that agent's
     model, and refuses anything else as `InvalidSettings` before anything is saved; without settings the rule's
     first mode and model are taken; another choice than *Rework* refuses settings.
   - A *Rework* whose parent waits for its children is deferred as today (`Offer.reworkComment`), and now keeps its
     settings in `Offer.rework`; the resume path (`WorkRun.reconcile` → `NextStep.resume`) builds the rework with
     them and counts no reading. The work record reads an older offer without the field as null.
   - A move by hand (`WorkRun.moves`) clears, once the store saved it, the moved non-manual issues' Needs-you reason
     and reading stop in every run that is not halted, since nothing else would ever clear them. Only an issue whose
     state the save changed counts as moved: a story move leaves its issues that stay where they stood, and a move to
     the state an issue stands in, with their warnings.
   - Contract: `PlanWorkIssue.reason` gains `BLOCKING_FINDINGS`, with `readingStopped` true as for `ROUND_LIMIT`;
     `ReadingRequest` gains optional `mode`, `agent` and `model`, all three or none (`WorkRequests.reading` refuses
     a part as `InvalidSettings`). The mock mode runs the same core, so it answers the new reason.
   - Red first: `ReadingCycleTest` (a blocking reading on the first of two stops; a rework below the limit keeps the
     rounds and the next blocking reading stops at the limit; a rework needs no words only when saved findings
     block), `NextStepTest` (a blocking reading offers no task, writes no state and defers nothing; a deferred rework
     resumes with its settings), `WorkRunTest` (a wrong agent, a wrong model and a wrong mode are refused and save
     nothing; the chosen or the first settings reach the offered task; a deferred parent's rework keeps its chosen
     settings; a hand move retires a FAILED issue's Needs-you and a stopped reading's stop), `WorkRunHttpTest` (a mode
     the Reworking rule does not offer and a partial set are refused; no settings rework in the rule's first),
     `WorkRecordTest` (an older offer without settings still opens).
3. **The card and the row** (`screen`). A card with an issue raised outside a stopped reading shows a warning mark
   leading to *Needs you*, as a stopped reading's line does. A row whose reason is `FAILED`, `UNPUSHED` or
   `INVALID_FINDINGS` opens, through `PlanningTaskChanges.open`, the task that raised it: the issue's latest ended
   task when its end is one that raises that reason (`planningRaisingTask`); where no such task can be told - a
   later task ended differently, or the issue is in a working state with no run - the row says the reason in words,
   and where the window cannot open the task it says why. `StoryWorkAttentionView` takes the window's
   `PlanningTaskChanges`, falling back to the board's, which only a shown planning screen sets. The *Rework* choice
   of a stopped reading opens the Reworking dialog: the findings, the Reworking rule's modes and agents
   (`PlanWorkRule.models`) with the first preselected, words optional when a finding blocks, and beside *Rework* the
   choices *Review anyway* and *One more reading*. `PlanningSource.resolveIssueReading` takes an optional
   `PlanningRework`. `planningReasonWords` has words for `BLOCKING_FINDINGS`; the fake story work answers it below
   the limit. Red first, widget tests (`needs_you_row_test.dart`, `reading_decision_test.dart`): a `FAILED` and an
   `UNPUSHED` row open the task that raised them; a row with no task to tell says its reason; a row without the
   window's hook says why; the dialog sends a rework with the first mode and model and no words; a card with a
   raised issue leads to *Needs you*; a rework with blocking findings needs no words and sends its settings.

Order: 1, 2, 3. Every test runs over fakes in seconds; no VM.

## Acceptance

- With today's work rules: a build in `AGENT` mode that ends done gets its reader in the Reading rule's first mode; a
  reading with blocking findings leaves the issue in Reading with no task and one Needs-you row, whose dialog starts
  the rework with the first mode and model preselected and needs no words; a task that did not finish leaves its
  card in its working state with a warning, and its row opens that task in the window, as does the row of a task
  that left commits unpushed and of a reader whose answer held no findings block; the Reworking dialog offers
  *Rework*, *Review anyway* and *One more reading*. Seen failing first: today's reader offer refused for its mode,
  and today's rework offered at once.
- A rework in a mode, with an agent or with a model the Reworking rule does not offer, or with only part of the
  three, is refused as `InvalidSettings` and saves nothing.
- A Needs-you row and a reading stop retire when the person moves the issue by hand.
- A window that predates `BLOCKING_FINDINGS` still draws such an issue as a stopped reading, since `readingStopped`
  is true for it, and its reading request without the new fields reworks in the rule's first mode and model.

## To be checked

- Whether the window still lists a task after it ended, so `open` reaches its result, and what `unavailable` says
  where it does not; read in the window's code only.
- Whether the window passes its `PlanningTaskChanges` to `StoryWorkAttentionView` in *Needs you*; until it does, a
  row of a board whose planning screen was never shown says that the window cannot open the task there.
- Whether the window overrides `PlanningSource.resolveIssueReading` anywhere (a fake in its tests): the new optional
  `rework` parameter must be added to such an override.
