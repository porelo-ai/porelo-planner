# PL38 — A Move Done At Once, Warned Where It Can Do Harm

```json
{
  "$schema": "issue-1.json",
  "id": "PL38",
  "title": "A Move Done At Once, Warned Where It Can Do Harm",
  "state": "Idea",
  "size": "-",
  "priority": "High",
  "rank": 25,
  "blockedBy": [],
  "covers": "A move on the board is saved at once, without Save and Undo, and each kind of move first warns where it can do harm - running agent tasks to stop or cancel, issues of other repositories moving along, a closed state - and asks the person to confirm or cancel"
}
```

**What must be true.** What the person does on the board happens at once: there is no list of unsaved moves and no
Save or Undo. Before a move that can do harm, the screen warns and the person confirms or cancels.

- **Running work.** A story or issue with agent tasks still running - in Implementing, Reading, Reworking or any
  working state - asks first: stop the tasks and move, or cancel the move.
- **Issues moving along.** A story's move moves its least advanced issues to the target. Today the screen shows only
  the story's own move, and a story in Implementing dragged to Finishing would set an issue of another repository
  that is still in Implementing to Finishing, unasked. The warning names each issue that moves, its repository and
  its old and new state.
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
