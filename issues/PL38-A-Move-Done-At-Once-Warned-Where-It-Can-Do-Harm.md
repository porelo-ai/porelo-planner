# PL38 — A Move Done At Once, Warned Where It Can Do Harm

```json
{
  "$schema": "issue-1.json",
  "id": "PL38",
  "title": "A Move Done At Once, Warned Where It Can Do Harm",
  "state": "Backlog",
  "size": "L",
  "priority": "Highest",
  "rank": 1,
  "blockedBy": [],
  "covers": "A move on the board is saved at once, without Save and Undo, and each kind of move first warns where it can do harm - running agent tasks to stop or cancel, issues of other repositories moving along, a closed state - and asks the person to confirm or cancel"
}
```

**Size:** L - a preview of a move in the core and the contract, the mock mode's answer to it, and the screen's move
flow rewritten from a list of unsaved moves to one save per move behind a warning; which move warns of what is
decided first.

**Estimate:** 113 min, the measured median of L. Uncertain: step 3 may find further kinds of move that need a warning,
each a new warning kind in the core, the contract and the screen.

**What must be true.** What the person does on the board happens at once: there is no list of unsaved moves and no
Save or Undo. A move with no warning is pushed the moment it is dropped, with no wait and no time to take it back.
Before a move that can do harm, the screen warns and the person confirms or cancels.

- **Running work.** A story or issue with agent tasks still running - in Implementing, Reading, Reworking or any
  working state - asks first: stop the tasks and move, or cancel the move.
- **Issues moving along.** A story's move moves its least advanced issues to the target. Today the screen shows only
  the story's own move, and a story in Implementing dragged to Finishing would set an issue of another repository that
  is still in Implementing to Finishing, unasked. The warning names each issue that moves, its repository and its old
  and new state.
- Every other kind of move is checked for whether it needs a warning.

## Why

A save commits and pushes straight to the planning store, in every repository it touches, with no gate and no
review. A move that silently carries other repositories' issues along, or leaves running agents working on a story
that moved on, does harm a confirmation would have prevented.

## Decided

- A move forward past states, such as Backlog straight to Finishing, warns and goes on after a confirmation.
- A move into a closed state, Finished or Rejected, is not asked about as such, since it can be moved back at any
  time; it warns only where something unfavorable could happen or the issues' states do not fit the target.
- A mistaken move is taken back by moving it back by hand; there is no Undo.
- A move back, such as Review to Backlog, keeps the story's run - its order, its tasks, each issue's progress and the
  reading rounds already spent - as its history, so a new start counts on from it. Where that history is kept is a
  task of its own.

## Plan

Read in today's code: the screen stages moves in `PlanningBoard._pending` (`move`, `moveIssue`, `undo`, `undoAll`,
reconciled against each new board) and sends them as one batch on Save (`save`, `savePlanningAction`; *Undo*, *Undo
all* and *Save* in `planning_view.dart`); a drop on a working state already opens the start dialog instead of a move.
The planner's `POST /projects/{project}/moves` checks the batch in `Moves.check` - a story's least advanced issues
move with it, a working state is refused (`EnteredOnlyByWork`), a story that does not fit its issues needs `confirm`
(`StoryStateMismatch`) - and `WorkRun.moves` refuses any move touching an item a started task owns (`WorkRunning`,
naming the story and its tasks); the answer names the issues moved only after they are pushed.

1. **A preview** (`core`, contract, `app`, mock). `Moves.preview` answers what a batch would do, writing nothing:
   each issue that moves with its repository, old and new state; each story; and the warnings - `RUNNING_WORK` (the
   story and tasks `WorkRun.moves` would refuse for), `ISSUES_ALONG` (an issue moved only because its story moved),
   `SKIPS_STATES` (a move forward past an open state), `STORY_MISMATCH` (today's `confirm` case) and any further kind
   the check of every move below finds. `POST .../moves` takes `preview: true` and answers it instead of saving; the
   mock mode answers it from its board. Red first, unit, in `MovesTest`: a story in Implementing moved to Review with
   an issue of another repository in Implementing previews that issue with its repository, `Implementing` and
   `Review`, and `ISSUES_ALONG`; integration in `app`: a preview leaves every repository's commits unchanged.
2. **One save per move** (`screen`). A drop or a move action previews first; with no warning it saves at once, with
   no wait before the save, with warnings the screen shows them and saves only on confirmation, `confirm` set where
   the story does not fit. For `RUNNING_WORK` the choice is stop and move - the story's stop as *Stop* sends it
   today, then the move - or cancel. The list of unsaved moves, *Undo*, *Undo all* and *Save* go, and with them the
   reconciling of staged moves. That also settles a move staged before the window rebinds the project to another
   repository: nothing is staged, and `HttpPlanningSource._bound` refuses a save racing a rebind with
   `ProjectChanged`. Red first, widget tests: a drop with a warning shows the issue that moves along and sends no save
   when cancelled; a drop without one saves once; unit: a move dropped while the project is rebound to another
   repository before its save goes out writes nothing to the new repository. Harm: a move written to a repository it
   was never meant for.
3. **Every other kind of move** is checked against the workflow and the work rules - a parent closed over its open
   sub-issues (refused today), a move out of a state an issue's stage record measures, a move back past Review - and
   each that can do harm gets its warning in step 1.

Order: 1, 3, 2.

## Acceptance

- A story with an issue of another repository behind it, dragged forward, shows that issue with its repository and
  states before anything is saved, and cancelling leaves every repository unchanged; a story with a running task asks
  to stop it first; a move with no warning is pushed at once, with no wait; the board shows no Save or Undo; a move
  racing a rebind of the project reaches no other repository.

## To be checked

- Whether the screen's watch already shows a saved move's new revision fast enough that the card does not jump back
  while the save is out, or whether the card keeps its new place until the answer.
