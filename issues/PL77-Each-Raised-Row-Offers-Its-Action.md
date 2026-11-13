# PL77 — Each Raised Row Offers Its Action

```json
{
  "$schema": "issue-1.json",
  "id": "PL77",
  "title": "Each Raised Row Offers Its Action",
  "state": "Review",
  "size": "L",
  "priority": "Highest",
  "rank": 5,
  "blockedBy": [],
  "covers": "Every row in Needs you offers what answers its reason: start again or stop after a failed next step or a refused save, move on or start again after a stop, open the task to push its commits, read again or go to Review after invalid findings"
}
```

**Size:** L - it crosses repositories: the window hands the start hooks to the boards it makes; and it changes the
contract, since a Start names the reason it passes over; four layers (core, app, screen, window) each with tests.

**Estimate:** 113 min, the measured median of L. Uncertain: how far `WorkRun`'s start path bends to replace a run
in one step under its lock; with [PL38](README.md#pl38) built first, "move on" and "go to Review" save at once with
its warning instead of staging a Save.

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

## Rules

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
4. **The row takes its start hooks from its board.** The window hands `startTask` and `agents` to
   `PlanningBoards` when it makes them, not only to `PlanningScreen`, so a board holds them before the screen was ever
   shown; `StoryWorkAttentionView` opens the Start dialog through its board's hooks and, without them, offers no Start
   and says why. "Move on" (after `STOPPED`) and "Go to Review" (after `INVALID_FINDINGS`) stage the move on the
   board and open the story with its Save, which the person still presses.

## Plan

- **What the plan's reading asked, and the answers.** A revision alone does not name a run: another window can start
  and stop again and come back to `STOPPED` at the same revision. So every call that acts on a run names the run.
  - **The run's id.** Each story's work on the board carries `run`, an opaque id the planner draws when a run starts
    and keeps in the work record. A row's Start passes over only the run whose id it names.
  - **Start's offers.** A Start that would replace a run's unstarted offers names that run in `replaces`. Without
    it, the planner refuses the Start with a conflict while unstarted offers stand, so a replayed Start never gets a
    second set of offers to launch.
  - **Stop.** Stop takes an optional `run`, and is refused with a conflict when another run stands. The screen
    always sends it.
  - **No Stop without the hook that ends the task.** The screen offers Stop only when its board holds the window's
    hook that ends a task (`taskChanges`); without it the row says why it offers no Stop. Recording a stop while the
    agent goes on running is worse than offering none.
- **Contract agreed with the window:** `passOver` (`issue`, `reason`, `revision`, `run`) on Start, `run` on a story's
  work, `replaces` on Start and an optional `run` on Stop, all carried by the screen alone; and `PlanningBoards` taking
  `startTask` and `agents` when it is made. The window never sends or repeats a story's Start itself, and
  already hands the board `taskChanges`.

- **Core (`planner/core`, `WorkRun`).** The start of a story's work takes an optional pass-over: issue, reason and the
  board revision the row showed. `WorkRun.allowed` accepts a run with reasons, or a halted one, only for a Start whose
  pass-over names exactly that issue's reason (`NEXT_STEP_UNAVAILABLE`, `SAVE_REFUSED`, `STOPPED`), no other issue's
  reason in the run, and no task at work; a changed reason or revision is a conflict (`RunConflict`). The old run is
  recorded stopped and the new one started in one step under the work record's lock. A move by hand whose save
  stands clears a halted run. The stop already takes an ended or halted run; it stays.
- **Contract and app (`planner.yaml`, the work controller).** The work request gains an optional `passOver`
  (`issue`, `reason`, `revision`); without it nothing changes. Written in the description first, then served.
- **Screen (`screen/`).** `PlanningBoard._startUnavailable` lets a row's own Start through for its reason;
  `PlanningBoard.stopWork` no longer asks for `atWork`, and the card and the row offer Stop while the run is not
  stopped; `PlanningBoards` takes `startTask` and `agents` when it is made and gives them to each board;
  `StoryWorkAttentionView` opens the Start dialog through them with the pass-over, or offers no Start and says why.
- **Window.** It hands the two hooks to `PlanningBoards` where it makes them: a change in the window's repository,
  asked of its agent in the channel; until it stands the row says why it offers no Start.
- **What building found.** The run's id was already drawn when a run starts and kept, checked, in every work
  record, so no record lacks one and none is given one on reading. A proposal no longer refuses a halted run, since
  the row's Start opens the Start dialog with a proposal first; the order alone checks the pass-over. A stop or a
  refused save raises every issue of the run with the same reason at once, so a pass-over is refused for another
  issue raised for another reason, not for another issue raised for the same one. The machines a start may choose
  are every board of the same project, which `PlanningBoards` knows itself, so the window hands no machines. "Move
  on" and "Go to Review" stage the issue's move to the done state of the work rule it stands under, and the row's `onStory` opens
  its story where the person saves it.
- **What the code reading found.** A Start passing over no reason is refused with `RunChanged` over any run that
  holds one, a stop's `STOPPED` too, whatever `replaces` says; `replaces` and the pass-over's run are checked against
  the standing run before anything else, so another run's id is always `RunChanged`; and Stop names the run shown
  when its confirmation opened, not the one standing when it is confirmed.
- **Tests, by level, red first.**
  - Unit, core (`WorkRunTest`): a Start passing over `SAVE_REFUSED` replaces the halted run; one naming a reason that
    changed meanwhile, another issue's, or another run's id, is refused with a conflict; a task still at work refuses
    it; a repeated Start without `replaces`, or naming another run, is refused while offers are unstarted; a Stop
    naming another run is refused; a move by hand clears a halted run. Harm: an agent started over a failure nobody looked at, or over work still running.
  - Integration, app: the work call with `passOver` against the description (`ApiMatchesDescriptionTest`) and its
    conflict. Harm: the window and the planner reading the contract differently.
  - Widget, screen (over `FakePlanningSource`): a `SAVE_REFUSED` row offers Start and sends the pass-over; an
    `INVALID_FINDINGS` row offers none; Stop shows on an ended run with reasons. Harm: a row that offers the wrong
    action.

## Acceptance

- From each raised row the person can do what its reason asks, as in *What must be true*; a Start from a row passes
  over only that row's reason and is refused when it changed; every new test was seen red.
- The suite, the screen's Maven build (`dart format`, `dart analyze --fatal-infos`, `flutter test`) and the
  documents' checks pass.
