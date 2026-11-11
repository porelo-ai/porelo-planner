# PL70 — A Save Answers Once Its Commits Stand

```json
{
  "$schema": "issue-1.json",
  "id": "PL70",
  "title": "A Save Answers Once Its Commits Stand",
  "state": "Backlog",
  "size": "L",
  "priority": "High",
  "rank": 1,
  "blockedBy": [],
  "covers": "A save answers once its local commits stand, the pushes run in the background with the repositories in parallel, the window shows each push's state and a failed push as a Needs-you entry, and a change the person made is never reported as made elsewhere"
}
```

**Size:** L - the Git store's save splits into a commit part and a push part, the store's interface and the planner's
answers carry a push state, and the screen shows it; one additive contract change the window reads.

**Estimate:** 113 min, the measured median of L. Uncertain: where the time of a save goes is measured first; if it
lies elsewhere than in reading files one Git process at a time, the plan's first step changes.

**What must be true.** A person who saves moves gets the answer within seconds, as soon as the save's commits stand
in the planner's clones; the pushes follow in the background, the repositories in parallel. The window shows that a
save is still being pushed, says so when a push failed and what the person can do, and never reports a change the
person made as made elsewhere.

## Plan

Read in today's code:

- `GitStore.saved` holds the project's lock, asks every repository's remote head (`remote.head`, one network call
  each), then `commitAndPush` copies every repository's `project.yml`, `issues/` and `stories/` into a scratch
  directory through `SaveFiles.write`, one `remote.read` per file - an `ls-tree` and a read, two Git processes per
  file, for every repository, changed or not. The stories index then reads every repository's index from the scratch
  copies (`IssueSource.local`). Each changed repository gets a commit, is read back, and the whole board is read
  again; then the repositories are pushed one after the other, the own repository last, and only then is the save
  answered.
- The window waits 30 s for a save's answer; a save of four story moves over three repositories took longer than
  that, and every push went through.

Steps:

1. **Measure** (no code kept): a save of moves over three repositories of a project of about fifteen, against local
   bare repositories, timed per part - heads, scratch copy, index, commits, read-back, board read, each push. Written
   into this issue before building on.
2. **Commit part fast** (`store/git`). The scratch copy takes only the changed repositories' files and the other
   repositories' issue indexes, read in one batch per repository (`git cat-file --batch` or one `git archive`), not
   one process per file. Red first, unit over a counting fake `Remote`: a save touching one repository of five
   reads no issue file of the other four.
3. **Push part in the background** (`store/git`, `store/api`). `save` answers the board of its local commits once
   they are read back; the pushes run on an executor of the store, the other repositories in parallel and the own
   repository last, as today, since its stories index names the others' states. The store keeps a push state per
   save - pending, pushed, or failed with the repository and the refusal's words - offered through
   `PlanningStore` (a default of "nothing pending" for stores that push nothing). A next save waits for the pending
   pushes (the lock stays held by them) and is refused `Busy` only after the lock's wait; a poll does not replace
   the board while a push is pending. A failed push leaves the local commit, so the person chooses: push again, or
   drop the save, which resets the clone to the remote. Red first, unit over a fake `Remote` whose push blocks: the
   save answers before the push ends; a failed push is told with its repository; no poll during the push brings
   back the older board.
4. **Contract and app** (`planner.yaml` first, `app`). The board's answer and its event carry the save's push state;
   `POST .../pushes/retry` and `.../pushes/drop` act on a failed one. Red first, integration in `app`: a save over
   a blocking fake answers 200 before its push ends, and the next board event says pushed.
5. **Screen** (`screen`). A pending push shows on the board; a failed push is a Needs-you entry with its
   repository, the words and the two choices. A board that changes by the person's own save, answered or not, is
   never reported as moved elsewhere: the screen tells its own moves from others' by the save's revision, not by
   whether its call answered in time. Red first, widget tests over the fake.

Order 1 to 5; every test runs over fakes in seconds; a VM is not needed.

## Acceptance

- A save of moves over three repositories of a project of about fifteen answers within 5 s; its pushes follow, and
  the board shows them pending until they are pushed. Seen failing first: today's save answering only after the
  last push.
- A push that fails shows as a Needs-you entry naming the repository, with push again and drop; neither loses a
  change silently.
- The person's own moves are never reported as moved elsewhere, also when the window gave up waiting for the answer.

## To be checked

- Where the time of today's save goes, per part (step 1).
- Whether the window outside the screen package needs a change for the push state, said to the coordinator as soon
  as the contract stands.
