# PL38 — A Move Done At Once, Warned Where It Can Do Harm

```json
{
  "$schema": "issue-1.json",
  "id": "PL38",
  "title": "A Move Done At Once, Warned Where It Can Do Harm",
  "state": "Implementing",
  "size": "L",
  "priority": "Highest",
  "rank": 3,
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
   the check of every move below finds. `RUNNING_WORK` names everything the stop would end: each running task, and
   each offer not yet started that the run's stop voids (`WorkRun.stop` voids them all, not only the moved item's).
   The preview is an operation of its own, `POST /projects/{project}/moves/preview`, never a field on `.../moves`:
   a planner that does not know it answers 404 and the screen then moves nothing, where an unknown field would be
   ignored and the batch saved. It answers a `digest` over the revision, the project's repository and branch, the
   batch, the warnings and the runs and offers they name; `POST .../moves` takes that `digest` and refuses a batch
   whose preview, computed again under the writer lock before anything is stopped or written, answers another one
   (`PreviewChanged`). Preview and save read the store and the work of one project entry. The mock mode answers the
   preview from its board. Red first, unit, in `MovesTest`: a story in Implementing moved to Review with
   an issue of another repository in Implementing previews that issue with its repository, `Implementing` and
   `Review`, and `ISSUES_ALONG`; integration in `app`: a preview leaves every repository's commits unchanged.
2. **One save per move** (`screen`). A drop or a move action previews first; with no warning it saves at once, with
   no wait before the save, with warnings the screen shows them and saves only on confirmation, `confirm` set where
   the story does not fit. The save goes against the revision the preview was answered for, so a board that changed
   between the preview and the confirmation answers `StaleRevision`, and the screen previews again and asks anew
   rather than saving what the person did not see; the save sends the preview's `digest`, so a confirmation counts
   only for the batch, the repository and the work it was shown. A drag or move action carries the revision and the
   state the person saw when it began, and the preview goes against that revision, so a board that changed during the
   gesture answers `StaleRevision` instead of being read against the new board.
   - **The card keeps its new place until the answer.** Read in today's code: a card stands where
     `PlanningBoard.displayedState` puts it, its entry in `_pending` while that has no problem; during a save
     `_pending` keeps the batch and a board the watch streams is held back (`_deferredSnapshot`) until the save
     ends; a saved answer is applied at once (`PlanningSnapshot.saved`) and the stories read again; a save that went
     unanswered in time keeps its moves `unanswered` until a streamed board shows them or `unansweredWait` passes,
     then says they were not saved. So nothing waits on the watch: the move stays an entry of `_pending` from the drop
     until its answer - shown at its target while it is previewed, confirmed and saved, back at its reported state
     when cancelled or refused - and `_pending` holds only that one move in flight, no longer a list of staged ones.
   - **One move at a time.** While a move is previewed, asked about or saved, or its save is unanswered, the board
     takes no other move or move action and says why (`Saving…`), as `move` already returns while `saving`; nothing
     is queued.
   - **Stop, then move.** The stop goes out as the story's stop does, then the save; while the planner still counts
     the tasks as running, the save is refused with `WorkRunning` and the screen says the tasks are still stopping,
     stops nothing more and saves nothing until the person moves again. How the stop is sent follows the screen's
     stop as it stands when this is built. For `RUNNING_WORK` the choice is stop and move - the story's stop as *Stop* sends it
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

4. **What the window reads changes, agreed with it before the build.** `PlanningSource` gains the preview and
   `saveMoves` the `digest` and `confirm`, which every source the window keeps, its mock board included, implements;
   the planner's mock mode answers the preview; the keys of the unsaved-move list, *Save* and *Undo* go, and the
   warning dialog's keys come; the window's guard against leaving with unsaved moves asks only about a save still
   out.

Order: 1, 3, 2, 4 agreed before 2.

**What the plan reading asked, and why not.**

- A confirmation used once only, kept by the planner: a save changes the revision, so a replayed confirmation is
  refused as stale, and the `digest` binds it to the batch, the repository and the work it was shown.
- A durable identity for each save with its recorded outcome, so a retry replays it: a save is sent against one
  revision, so a second one after a lost answer is refused as stale and never writes twice; an unanswered save keeps
  the board's existing unanswered state and its deadline, and the board takes no other move meanwhile.
- A stop answering only once every task has ended: the planner refuses the move while any task still runs
  (`WorkRunning`), which no screen can pass around, so a stop not yet ended moves nothing.

- **Agreed with the window, with two changes.** `RUNNING_WORK`'s list stays open to starts the window handed to Porelo
  but has not reported to the planner yet: the window adds those under the same kind, and the `digest` does not cover
  them. `PlanningSource.saveMoves` takes `digest` and `confirm` as named parameters, `digest` required, so a source not
  updated fails to compile instead of saving without a preview.
- **Delivery with the window.** The window's own change for the removed Save and Undo keys and the new warning keys
  goes out in the same push batch as this one, with the window's pin moved to it; no batch carries one side alone.
  This change reaches the test VM only after the window's walk that still uses the Save keys has ended.

## Acceptance

- A story with an issue of another repository behind it, dragged forward, shows that issue with its repository and
  states before anything is saved, and cancelling leaves every repository unchanged; a story with a running task asks
  to stop it first; a move with no warning is pushed at once, with no wait; the board shows no Save or Undo; a move
  racing a rebind of the project reaches no other repository.
- A move against a story's issues: Cancel leaves the card where it stood and sends nothing; a move that fits its issues
  asks nothing.
