# PL146 — Start Work Runs Its Starts On The Board

```json
{
  "$schema": "issue-1.json",
  "id": "PL146",
  "title": "Start Work Runs Its Starts On The Board",
  "state": "Implementing",
  "size": "M",
  "priority": "High",
  "rank": 66,
  "blockedBy": [],
  "covers": "The screen's Start work hands the task starts of a taken order and their confirmation to the board's run of the story, so the dialog closes once the order is taken and the first start is asked, and a later refusal reaches the board and the window; a refusal before anything starts stays in the dialog"
}
```

**Why this size.** M - the screen package's story work and task runs, the dialog, their tests, and a change of when
`StoryWork.start` answers, which the window builds against.

**What must be true.** A person who presses Start work sees the dialog close as soon as the planner took the order and
the first task's start is asked, while the start goes on on the board with its steps shown, instead of a dialog that
waits for every task's image build.

## Plan

- Today `StoryWork.start` (`screen/lib/src/story_work.dart`) awaits the planner's order, then each task's
  `run.start` (`screen/lib/src/task_runs.dart`), which awaits the window's Start hook and answers after the image
  build, then `run.confirmed`; `origin.endStoryStart` ends the board's start token only after all of them. The dialog
  (`screen/lib/src/ui/story_work_dialog.dart`) pops only when `start()` answers, and `StoryWork` is disposed with it,
  so its `owned()` (which checks `_disposed`) stops every start still to come once the dialog goes.
- `PlanningRun` gets a method that takes the order's tasks, the Start hook and the run's epoch and starts them in order,
  then confirms them and ends the board's start token, owned by the run alone: the epoch (a Stop or a confirmed save
  that stops the run) still cuts it, the dialog's disposal no longer does. A refusal or failure of a start or of the
  confirmation is kept on the run, where the board shows it as the story's problem and the window learns of it as
  today through `onStarted` and the task changes.
- `StoryWork.start` answers true once the order is taken and valid and the run has asked the first task's start; a
  refusal of the order itself (stale revision, not offered, not yet built) still answers false with its words in the
  dialog. The dialog then pops as now on true.
- Red first, in `screen/test/story_work_start_test.dart` over the fake source and a Start hook that waits: `start()`
  answers before the hook answers; disposing the dialog's `StoryWork` then still lets the second task start and the
  confirmation be sent; a refusal of the second start reaches the board's run, not the closed dialog; a Stop between
  the two starts still prevents the second. Harm: a start the person cannot see stopped, a refusal lost with the
  dialog, a task started twice or after a Stop.

## Acceptance

- The tests above, seen red against today's start; the screen's suite passes; the window's side closes the dialog on
  the earlier answer.
