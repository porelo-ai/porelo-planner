# PL05 — Moves Saved All Or None

```json
{
  "$schema": "issue-1.json",
  "id": "PL05",
  "title": "Moves Saved All Or None",
  "state": "Implementing",
  "size": "L",
  "priority": "Medium",
  "rank": 4,
  "blockedBy": ["PL04"],
  "covers": "Moves saved all or none against the revision, committed and pushed, the stories index rewritten; working states only by work; a mismatching story state saved on confirmation; a parent Finished only after its sub-issues"
}
```

**Why this size.** checked moves, one commit and push per save against the board's revision, the stories index rewritten
with the stories library, and the confirmation of a story state its issues do not fit.

**What must be true.** The window saves all pending story and issue moves together against the revision it shows:
all are committed and pushed, or none is. A move into a working state is refused, so the window offers that work's
start instead. A story moved into a state its issues do not fit is answered with a warning naming each such issue and
its state, and saved only when the call confirms it. A parent issue is moved to Finished only when every sub-issue
under it is Finished. After a save the stories index reads as the project's own index command writes it.

## Plan

- **The call, described first** in `planner/app/src/main/resources/openapi/planner.yaml`: `POST
  /projects/{project}/moves` with `{revision, moves: [{story | issue, to}], confirm}` answers `{revision, moved}`,
  `moved` the ids of the issues whose state changed. Refusals as `application/problem+json` under the names the
  window's mock already uses: `StaleRevision(revision)`, `NoSuchStory(story)`, `NoSuchIssue(issue)`,
  `EnteredOnlyByWork(item, to)`, `StateNotAllowed(item, from, to, reason)` for a target that is not a waiting or
  closed state, `MoveNotAllowed(item, from, to, reason)` for a move that names no item, one twice, or a parent closed
  before its sub-issues, `StoryStateMismatch(story, to, issues)` when not confirmed, `Busy` when the project's lock is
  not free within 30 s, and `PartlySaved(pushed, failed)` when a later repository's push fails.
- **`core`, plain Java:** `Moves.check(board, workRules, moves, confirm)` answers the planned writes, each issue's new
  state and each story's stated state, or throws the refusal. A story with issues moves its least advanced issues
  and leaves those further on; one without issues takes the state itself. A target that is a key of the work rules is
  `EnteredOnlyByWork`; a parent moved into a closed state while a sub-issue, after this batch, stays open is refused
  naming the sub-issue. A story whose state, derived after the batch, is not the target is `StoryStateMismatch`
  naming each issue that does not fit and its state; with `confirm` the story's head states the target. Story moves
  apply before issue moves, as the window's mock does.
- **`store/api`:** `PlanningStore.save(revision, writes)` answering the new board; the default refuses it as not
  offered, so the mock store keeps answering that until it moves boards in memory.
- **`store/git`:** `GitStore.save` holds the project's lock (now with a 30 s bound, `Busy` after it), asks every
  remote for its head and refuses `StaleRevision` when the revision is not the board's. Each repository with a write
  is checked out from its bare clone into a temporary directory with a temporary index; the issue heads' `state`
  (and a story head's `state`) are rewritten by an exact edit of the one `state` member inside the head's fence,
  refused when there is not exactly one; `IssuesIndex.write` rewrites its issue index; in the project's own
  repository `StoriesIndex.write` rewrites the stories index over the new issue indexes of every repository. Only the
  edited heads and the files the library reports written are added; `write-tree` and `commit-tree` with the fetched
  commit as parent make one commit per repository, pushed without force. A refused push moves no ref, so nothing is
  rolled back and the answer is `StaleRevision`. Other repositories push first and the own one last; a later failure
  is `PartlySaved` naming those pushed, never retried. Then the fetched refs move to the pushed commits, the board is
  read again and swapped whole, and its listeners tell the windows. Each issue's file path is kept inside `store/git`
  when it is read.
- **The plan reading, taken in:**
  - Revisions: the call's revision, the board's and the one the remotes' heads form must agree, checked before the
    moves; `StaleRevision` names the remotes' revision.
  - Writing: the files of `issues/**` (and `stories/**` in the own repository) come into a temporary directory inside
    the project's own cache directory only through `Remote.read`, which takes plain files within the size bound
    alone; nothing is checked out, so no link reaches the disk, and every write stays under that directory, which is
    deleted in `finally`. A head's `state` member is edited only inside the fence that opens the file; a story's is
    inserted when a mismatch is confirmed and removed when a story with issues fits its target again. Only the edited
    heads, `issues/README.md`, `issues/<group>/README.md` and `stories/README.md` are added, with `GIT_INDEX_FILE` of
    the call's own pointing into that directory.
  - Pushing: always `<commit>:refs/heads/<branch>`, the remote's default branch resolved with `ls-remote --symref`
    and checked as a branch name; `--force-with-lease=refs/heads/<branch>:<fetched>` makes the push a compare and
    swap, so a remote rewound or moved since the fetch refuses it, and the commit, a child of the fetched one, is a
    fast-forward in every other case.
  - Half saves: every commit, the own repository's included when its stories index changed, is built and its part
    read back from the bare clone before the first push; after the first push any failure, a refused push included,
    is `PartlySaved` naming the repositories pushed; each pushed repository's fetched ref moves at once.
  - Checks: working states are the keys of the work rules as read, faulty or not, and work rules with faults refuse
    the batch; after the batch no parent stands in a closed state with an open sub-issue, whichever was moved (a
    closed state, Finished or Rejected, closes a sub-issue); an issue with only an index row is not savable; `moved`
    names each issue the batch moves once, as the window expects, and only issues whose state changes are written.
  - The body's ids are checked as ids before they are looked up, an unreadable body is refused as such, and the
    parameters stay strings (`issues` and `pushed` as comma-joined lists).
  - The lock: the process lock and the file lock are both taken within 30 s, the file lock by polling `tryLock`;
    `requireActive` is checked once it is held. `ChangesRegistry.saved(project)` tells a followed project's windows.
- **`app`:** `BoardController` serves the call, maps the refusals in `Problems`, and tells the new board through
  `Changes.saved()`.
- **Red first** (unit tests over fakes): `MovesTest` - a story in Backlog with issues in Backlog and Review, moved to
  Rejected, moves the Backlog issue alone and is a mismatch unless confirmed; a move into a working state, a state
  not waiting or closed, a doubled item, an unknown item, a parent closed before its sub-issue are refused.
  `GitStoreSaveTest` against bare repositories on disk - a save is one commit per repository visible in a fresh clone,
  its indexes equal to what the library writes; a remote that moved is `StaleRevision` with nothing changed locally or
  remotely; two saves on one revision, one wins; a save waits for a refresh. `MovesEndpointTest` - each refusal's
  name and parameters, and the told board.

## Acceptance

- The red-first tests seen red, then green; a save against the Git HTTP test server visible in a fresh clone.
