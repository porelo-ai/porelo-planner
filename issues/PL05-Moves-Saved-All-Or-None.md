# PL05 — Moves Saved All Or None

**Status:** Analyzed; blocked by [PL04](README.md#pl04).
**Size:** L - checked moves, one commit and push per save against the board's revision, the stories index rewritten
with the stories library, and the confirmation of a story state its issues do not fit.

**What must be true.** The window saves all pending story and issue moves together against the revision it shows:
all are committed and pushed, or none is. A move into a working state is refused, so the window offers that work's
start instead. A story moved into a state its issues do not fit is answered with a warning naming each such issue and
its state, and saved only when the call confirms it. A parent issue is moved to Finished only when every sub-issue
under it is Finished. After a save the stories index reads as the project's own index command writes it.

## Plan

- `POST /projects/{project}/moves` with `{revision, moves: [{story|issue, to}], confirm: bool}` -> `{revision,
  moved}`; refusals `stale-revision` (with the current revision), `move-not-allowed(item, from, to, reason)`,
  `entered-only-by-work(item, to)`, `story-state-mismatch(story, issues: [{issue, state}])` when not confirmed.
- `core`: `Moves.check(board, workflow, workRules, moves)` - a story move changes its least advanced issues and
  leaves those further on; a story without issues takes its own state; a target that is a working state (a key of
  the work rules) is refused; a parent to Finished with a sub-issue not Finished is refused, naming it.
- `store/api`: `save(revision, changes)`; `store/git`: under a lock per project, fetch, refuse when the revision
  moved, rewrite each changed issue's status line and index row and each story's head, rewrite the stories index with
  the `porelo-stories` library over the clones, commit per repository and push; a refused push rolls the local
  commits back and answers `stale-revision`. Each repository's push is all or none; a save over several
  repositories whose later push fails is reported naming the repositories already pushed, never retried silently.
  The writing in the unpushed stories-git state-setting branch is the model.
- **Red first:** `MovesTest` - a story in Backlog with issues in Backlog and Review, moved to Rejected, moves the
  Backlog issue alone; a move into a working state is refused; a story moved where its issues do not fit warns and is
  saved only with `confirm`. `GitStoreSaveTest` - a push refused because the remote moved changes nothing, locally or
  remotely; the stories index after a save equals the library's output.

## To be checked

- `porelo-stories` is published under LGPL-3.0-or-later before the planner links it; until then it is GPL-3.0-or-later
  and a linked executable would be a combined work under the GPL.

## Acceptance

- The red-first tests seen red, then green; a save against the Git HTTP test server visible in a fresh clone.
