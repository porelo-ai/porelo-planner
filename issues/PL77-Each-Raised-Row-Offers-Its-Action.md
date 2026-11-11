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

## Open questions

- Which reasons may a Start from the row pass over, and does a halted run take a start without a Stop first?
- May Stop work end a run that is not at work?
- How does the window hand the row its start hooks?
