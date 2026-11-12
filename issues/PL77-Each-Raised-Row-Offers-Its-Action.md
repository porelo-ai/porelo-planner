# PL77 — Each Raised Row Offers Its Action

```json
{
  "$schema": "issue-1.json",
  "id": "PL77",
  "title": "Each Raised Row Offers Its Action",
  "state": "Idea",
  "size": "-",
  "priority": "High",
  "rank": 1,
  "blockedBy": [],
  "covers": "Every row in Needs you offers what answers its reason: start again or stop after a failed next step or a refused save, move on or start again after a stop, open the task to push its commits, read again or go to Review after invalid findings"
}
```

**What must be true.** A person who sees a row in *Needs you* can answer it from the row: `NEXT_STEP_UNAVAILABLE` and
`SAVE_REFUSED` look at the failure and start again or stop; `STOPPED` moves the issue on or starts again; `UNPUSHED`
opens the task to push its commits; `INVALID_FINDINGS` reads again or goes to Review.

Today's code stands in the way, read in it:

- A raised row holds its story's Start: the screen's `PlanningBoard._startUnavailable` answers the run's attention
  before anything else, so "start again" cannot be offered from the row it answers.
- A halted run (`SAVE_REFUSED`) takes no start until it is stopped (`WorkRun.allowed`), and a move by hand does not
  clear a halted run.
- The screen offers Stop work only while the run is at work (`PlanningBoard.stopWork`), though the planner's stop
  takes an ended or halted run and turns every reason into `STOPPED`.
- `StoryWorkAttentionView` has no start hooks (start task, agents, machines), so a row cannot open the Start dialog;
  "move on" and "go to Review" could only stage a move the person still saves.

## Proposed rules

1. **A Start from a raised row passes over the reason it answers, and only that.** The row of
   `NEXT_STEP_UNAVAILABLE`, `SAVE_REFUSED` or `STOPPED` offers Start again; the Start names the issue, the reason and
   the board revision the row showed, and the planner starts a new run of the story in place of the old one, refusing
   with a conflict when the run's reasons changed meanwhile. It never passes over another issue's reason in the same
   run, nor work still at work; `FAILED`, `ROUND_LIMIT`, `BLOCKING_FINDINGS`, `INVALID_FINDINGS` and `UNPUSHED` keep
   their own actions and offer no Start. The story card's Start stays held by the run's attention as today.
2. **A halted run takes that Start without a Stop first.** For `SAVE_REFUSED`, the planner records the halted run as
   stopped and starts the new one in one step under the work record's lock (`WorkRun.allowed` accepts a halted run
   only for a Start that names its reason). A move by hand whose save stands clears a halted run, since the refused
   save it held is then moot.
3. **Stop work ends any run that is not stopped yet**: at work, ended with reasons, or halted, as the planner's stop
   already does. The screen offers Stop on a raised row and on the story card whenever the run is not stopped
   (`PlanningBoard.stopWork` no longer asks for `atWork`); afterwards each reason reads `STOPPED`.
4. **The row takes its start hooks from its board.** The window hands `startTask`, `agents` and `machines` to
   `PlanningBoards` when it makes them, not only to `PlanningScreen`, so a board holds them before the screen was ever
   shown; `StoryWorkAttentionView` opens the Start dialog through its board's hooks and, without them, offers no Start
   and says why. "Move on" (after `STOPPED`) and "Go to Review" (after `INVALID_FINDINGS`) stage the move on the
   board and open the story with its Save, which the person still presses.

## Open questions

- Do the four proposed rules hold as written?
