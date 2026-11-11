# PL26 — The Mock Board Saves Moves

```json
{
  "$schema": "issue-1.json",
  "id": "PL26",
  "title": "The Mock Board Saves Moves",
  "state": "Review",
  "size": "M",
  "priority": "Medium",
  "rank": 15,
  "blockedBy": [],
  "covers": "The mock mode saves moves into its in-memory board with a new revision and a change event, answers PartlySaved for one fixed story, and serves a story with sub-issues so a refused move can be reached"
}
```

**Size:** M - a save and a story-state rule in `MockStore`, one board per project, two additions to the mock board,
in `app` and the screen package's copy of the mock board; the mock board is what the window's planning tests read.

**What must be true.** The window's planning tests can save moves against the mock mode and see them. This issue
builds the mock mode's moves in memory, which [PL10](README.md#pl10) leaves to it:

- A save changes the in-memory board, answers a new revision and tells the change to the board's windows. A reset
  restores the board.
- One fixed story id answers PartlySaved, naming a repository pushed and one not.
- One mock issue has a sub-issue, so a move of the parent that its open sub-issue forbids answers MoveNotAllowed.

## Plan

As built:

1. **One board per project** (`StoreConfiguration`, `MockStore.copy`). The mock opener answers a new `MockStore` on
   the board read once from the class path, so a move in one project leaves another's board as it was; forgetting
   the projects on `POST /mock/reset` drops the copies, and `MockStore.reset` restores a store's first board.
2. **Save** (`MockStore.save`, under the store's monitor, so concurrent requests neither lose nor double a move).
   It refuses a revision other than the current one (`StaleRevision`), sets each issue's new state and worker and
   each story's stated state or its removal, derives every story without a stated state from its issues, and
   answers the board with the next revision, `mock@2` and on. A priority stays unsaved
   (`UnsupportedOperationException`, answered `NotOffered` as before).
3. **Story state** (`MockStore.storyState`): the earliest open state of the issues in workflow order; with every
   issue closed, the first issue's state when all are rejected, else the completed state. The store interface's
   `Workflow` names the closed states but not which reject; the mock takes the first closed state as the completed
   one and every later one as rejecting, which holds for the mock's workflow (Finished, Rejected).
4. **PartlySaved** (`MockStore.PARTLY_SAVED`, ST292). A batch moving an issue of ST292 whose writes reach more than
   one repository saves the writes of the first repository in push order - the other repositories in the project's
   order, the own repository last, as the Git store pushes them - answers that board with a new revision for every
   read and window, and throws `PartlySaved` naming it as pushed and the next as failed. A story move of ST292 to
   Review answers 502 with `pushed` porelo and `failed` porelo-host.
5. **A sub-issue** (`mock/board.json` and the screen package's `planning_data.dart`, which `MockModeTest` compares
   story by story with its issues). B540.1 in porelo, parent B540, in Backlog, listed with ST291, which stays in
   Backlog; moving B540 to Finished answers `MoveNotAllowed`.
6. **Start in the mock.** A work order saves nothing, so `StoryWorkHttpTest` still sees `mock@1` after it; the move
   into the working state is saved when the window reports the task STARTED (`WorkRun`, `NextStep.started`), which
   in the mock now changes the board instead of halting the run with `SAVE_REFUSED`.

## Acceptance

- `MockStoreTest` (unit): a save answers `mock@2` with the move and leaves the board before it unchanged; a stale
  save is refused and changes nothing; a story without a stated state follows its issues, a stated one is kept and
  its removal derives it again; the derivation of open, closed and rejected issues; ST292 over two repositories saves
  porelo and names porelo-host; over one repository it saves whole; one copy's move leaves another's board; a reset
  restores the first board; eight concurrent saves end at `mock@9` with every move; a priority is not saved. Each
  seen red under a deliberate break, then green.
- `MockModeTest` (integration over HTTP against `--mock`): a move answers `mock@2`, shows in the next read and on an
  open event stream; ST292 answers 502 `PartlySaved` with `pushed` and `failed`; B540 to Finished answers
  `MoveNotAllowed`; a reset brings back `mock@1`, and one project's move leaves another at `mock@1`; a worker change
  answers as a move does.
- The app's whole suite and the screen package's tests green.

## Readings declined

- `/mock/reset` also resets the board the per-project copies are made from, which no save writes into; the reset is
  kept there so a store used without copies starts again alike, and costs nothing.
- The mock stores a target state without checking it against the workflow: every move reaches it only through the
  core's moves, which refuse a state the workflow lacks with `StateNotAllowed` before anything is saved.
