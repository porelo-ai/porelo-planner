# PL147 — A Task Event's Save Survives A Moved Board

```json
{
  "$schema": "issue-1.json",
  "id": "PL147",
  "title": "A Task Event's Save Survives A Moved Board",
  "state": "Implementing",
  "size": "S",
  "priority": "High",
  "rank": 67,
  "blockedBy": [],
  "covers": "The save the planner makes for a task's event, its start or its end, is made once more against the board as it is now when the store changed in the meantime and pushed nothing, so a commit to the project's repository during a task's start no longer halts its run with saving refused"
}
```

**Why this size.** S - one path of the core's work run and its unit tests over the fake store; no contract.

**What must be true.** A person whose story's task starts while anyone else changes the project's planning sees the
run go on, instead of a run halted with "saving its findings was refused" while its task works on.

## Plan

- Today `WorkRun.report` (`planner/core`) reads `store.board()` for a task's event, computes the writes (for STARTED,
  `NextStep.started` moves the issues and the story into the working state; for ENDED, the next step), and
  `WorkRun.save` calls `store.save(base.revision(), writes)`. When the store took another commit in between, the Git
  store answers `StaleRevision`; `save` catches every `StoreException` alike, halts the run, voids its offers and marks
  each issue `SAVE_REFUSED`, though nothing was pushed. Seen in a live run: a task's STARTED save refused at the moment
  the project's own repository gained a story, the task working on while its run stood halted.
- On `StaleRevision` with nothing pushed, the event's board is read anew (`store.refresh()`), the event is judged again
  on it (ownership, the writes) and saved once more, as `HandedIdeas` already does for a handed-in idea; a second
  `StaleRevision`, or an event the new board no longer allows, halts the run as today, with its reason.
- Red first, in the core's work run tests over the fake store: a store whose board moves once between the event's read
  and its save; the STARTED event is saved, the run is not halted and the issue stands in the working state; a board
  that moved so the binding no longer owns its issues still halts it, naming why; a store that moves on every save
  halts it after the second try. Harm: a running task whose run the planner gave up, and a save written twice.

## Acceptance

- The tests above, seen red against today's save; the core's suite passes.
