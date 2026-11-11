# PL26 — The Mock Board Saves Moves

```json
{
  "$schema": "issue-1.json",
  "id": "PL26",
  "title": "The Mock Board Saves Moves",
  "state": "Backlog",
  "size": "M",
  "priority": "Medium",
  "rank": 15,
  "blockedBy": [],
  "covers": "The mock mode saves moves into its in-memory board with a new revision and a change event, answers PartlySaved for one fixed story, and serves a story with sub-issues so a refused move can be reached"
}
```

**Size:** M - a save and a story-state rule in `MockStore`, one board per project, two additions to the mock board,
in `app` alone; but the mock board is what the window's planning tests read, so its new items and the changed
answer of a Start in the mock are agreed with the window.

**What must be true.** The window's planning tests can save moves against the mock mode and see them. This issue
builds the mock mode's moves in memory, which [PL10](README.md#pl10) leaves to it:

- A save changes the in-memory board, answers a new revision and tells the change to the board's windows. A reset
  restores the board.
- One fixed story id answers PartlySaved, naming a repository pushed and one not.
- One mock issue has a sub-issue, so a move of the parent that its open sub-issue forbids answers MoveNotAllowed.

## Plan

Read in today's code: `POST /projects/{project}/moves` (`BoardController.saveMoves`) checks the batch with
`Moves.check`, which already refuses a parent closed over an open sub-issue (`checkParents`, `MoveNotAllowed`), and
then calls `PlanningStore.save`; `changes.saved(project)` then tells every window the store's board if its revision
moved (`ChangesRegistry.saved`, `Changes.saved`). `MockStore` (`app/mock`) overrides neither `save` nor `storyState`,
whose defaults throw `UnsupportedOperationException`, so today a move in the mock mode answers `NotOffered`, a story
move with issues already in `Moves.check`. Its revision is fixed (`mock@1` in `mock/board.json`), and
`StoreConfiguration.projects` hands the one `MockStore` bean to every project given. No issue of `mock/board.json`
has a `parent`. The rest - the event and the reset - needs nothing new: a new revision is told by the existing path,
and `MockController.reset` already forgets every project.

1. **One board per project** (`StoreConfiguration`, `MockStore`). The opener answers a new `MockStore` on the board
   read once from the class path, so a move in one project leaves another's board as it was; `reset` restores the
   bean's board, and forgetting the projects drops the copies. Unit.
2. **Save and story state** (`MockStore`). `save(revision, writes)` refuses a revision other than the current one
   (`StaleRevision`), sets each issue's new state and each story's stated state or its removal (`Writes.issues`,
   `stories`, `unstated`), derives every story without a stated state from its issues, and answers the board with the
   next revision, `mock@2` and on. `storyState` derives as the stories index does: the earliest open state of the
   issues in workflow order, otherwise a closed state. Red first, unit in a new `MockStoreTest`: a save of B542 to
   Review answers a board with a new revision and B542 in Review, and the board before it is unchanged. Harm: the
   window's tests of a save pass against a mock that never saved.
3. **PartlySaved** (`MockStore`). A batch moving issues of the fixed story in more than one repository saves those of
   the first repository in the project's order, answers a board with a new revision for them, and throws
   `PartlySaved` naming the next one, as the Git store leaves a save whose later push failed. Proposed story: ST292,
   whose issues lie in porelo, porelo-host, porelo-frontend and porelo-message-matrix. Unit.
4. **A sub-issue** (`mock/board.json`). B540.1 in porelo, parent B540, in Backlog, listed with ST291, so ST291 stays
   in Backlog; moving B540 to Finished answers `MoveNotAllowed`. Integration in `MockModeTest`.
5. **Over HTTP** (`MockModeTest`, integration): a move answers a new revision, the next read and an open event
   stream show it, ST292 answers 502 `PartlySaved` with `pushed` and `failed`, and `POST /mock/reset` brings back
   `mock@1`. `StoryWorkHttpTest.mockModeServesTheOperationsThroughARealHttpServer` asserts today that a Start in the
   mock leaves the revision at `mock@1`; with a save, `WorkRun` moves ST14's issue into Implementing, and the
   assertion turns to that.

## Acceptance

- `MockStoreTest` and the new `MockModeTest` cases seen red on today's code, then green; the app's whole suite and
  the native test in mock mode green.
- Against `--mock`: a move shows in the next read and on the stream, ST292 answers PartlySaved, B540 to Finished
  answers MoveNotAllowed, a reset restores `mock@1`.

## To be checked

- With the window's agent: the story for PartlySaved and the sub-issue's id, whether the window's tests count the
  mock's issues or rely on `mock@1` after a Start, and whether a Start in the mock may now move issues.
- Whether the store interface's `Workflow` says enough to derive a story's state as the stories index does: it names
  the closed states, not which of them is the rejecting one.
- In which order the Git store pushes a save's repositories, so the mock's PartlySaved names them as it would.
- Whether the Git store lists a sub-issue among its story's issues, so the mock board does the same.
