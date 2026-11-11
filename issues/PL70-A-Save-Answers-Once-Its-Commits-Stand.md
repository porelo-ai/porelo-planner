# PL70 — A Save Answers Once Its Commits Stand

```json
{
  "$schema": "issue-1.json",
  "id": "PL70",
  "title": "A Save Answers Once Its Commits Stand",
  "state": "Review",
  "size": "M",
  "priority": "High",
  "rank": 1,
  "blockedBy": [],
  "covers": "A save answers once its local commit stands in the planner's clone, a restart loses none, the pushes follow in the background with the own repository last and a rejected push replayed, and a push that conflicts drops the save and says so on the board"
}
```

**Size:** M - one unit in `store/git` behind a small interface, the save in `GitStore` rewired to it, and the app
starting its pusher; no contract change.

**Estimate:** 67 min, the measured median of M.

**What must be true.** A person who saves moves gets the answer in about a second, as soon as the save's commit stands
in the planner's clone; a restart loses no save. The pushes follow in the background. A push the remote rejects is
replayed on the remote's new head and pushed again; one that really conflicts drops the save, and the board says
which repository and which items, so the person moves them again.

## Plan

Read in today's code: one bare clone per repository (`GitRemote`); a save (`GitStore.saved`) asks every remote head,
copies every file of every repository through Git into a scratch directory (`SaveFiles.write`), builds each commit
with `commit-tree` on a temporary index with several processes per file, reads the whole board again, then pushes one
repository after the other and answers. A planner commit is held by no ref until its push moves
`refs/planner/fetched`.

The unit, `LocalSaves` in `store/git`, over `LocalRefs` (`GitLocalRefs` in the clones):

- `record(parents, commits)` makes a save's commits, built by `GitRemote.commit`, the repositories'
  `refs/planner/local`, all or none and only while each still stands at the commit the save was made on (compare
  and swap with `update-ref`); the ref exists only while a save waits.
- `push()` starts one round in the background, or one more after the running one: the other repositories pushed in
  parallel, the own repository last and only once the others are, since its stories index names their states. A push
  leases `refs/planner/fetched`, which moves on success. On a rejection it fetches into a ref of its own and makes
  each save again on the remote's commit (`diff-tree`, `read-tree`, `update-index`, `commit-tree`, no working tree)
  while the remote changed none of the save's paths and the two did not both add an issue file, which could carry the
  same number. Otherwise the save is dropped - `local` removed, `fetched` moved to the remote - and the own
  repository's waiting saves with it, since its stories index names the dropped states. No text merge: two generated
  indexes merged by lines are not an index the generator writes.
- `state()` answers each repository's push: pending, pushed, failed with its words, or dropped with its paths.
- A new start finds `local` and pushes it with the first board.
- The refs are guarded by the unit's own monitor; the project lock is never held by the pusher.

Around the unit:

- `GitStore.save` checks only that the window's revision is the board's and asks no remote head; it answers once the
  commits stand, with the board read from them.
- `GitRemote` reads a commit's planning files (`project.yml`, `issues/`, `stories/`, `stats/`) with one `ls-tree` and
  one `cat-file --batch` and keeps the last two commits of each repository; a commit takes a fixed seven processes
  whatever the number of files. `GitRunner` feeds a call an input on a thread of its own.
- The board's `faults` name a failed push and a dropped save with its items; the window shows them today. A check
  reads a repository with a waiting save at that save and never fetches over it, and starts a round of pushes, so a
  failed one is tried again.
- `GitProjects` gives each store its `LocalSaves`; retiring the store stops the pushes.
- The contract is unchanged; the Git store no longer answers `PartlySaved`, which stays in it for another store.

Tests, each seen red by a deliberate break:

1. `LocalSavesTest`, over bare repositories served by a local HTTPS server: a save stands only in the clone; a new
   start pushes a save left unpushed; a rejected push over other files is made again and keeps both; one over the
   same file is dropped and named; the own repository waits for the others; a save made during a push goes with the
   next round; a save on an older commit is refused.
2. `GitStoreSaveTest`: a save over a change to other files is made again on it; a failed push is told on the board and
   the own waits; a push over a change to the same issue is dropped, named, and the own stories index dropped with it;
   an idea over an issue added elsewhere is dropped. `GitStoreTest`: a round of pushes keeps the board when nothing
   changed.
3. `GitRemoteTest`, `CommitFilesTest`, `GitRunnerTest`: each commit read as it holds its files after another; a link
   or an oversized file still refused; a commit holds exactly the save's changes and keeps a file's mode; a call fed an
   input takes all of it while it prints.

- **Code reading:** read by another model; each finding checked in the code. Fixed, each with a test seen red: a
  push that landed without being recorded, followed by another save, was taken for a conflict and dropped both - the
  remote's commit found among the saves is now the new base; the own repository could be pushed while a save of
  another, made during the round, still waited - it now goes only once no other waits; a push the remote refused
  while it stood still was made again under new commits three times - it now fails with the remote's words. Fixed
  without a test, as nothing is lost: the state told pushed set under the refs' monitor, a board rebuilt after a push
  tried again when a check swapped it meanwhile, a listener's failure kept from ending the pushes, a failure of a
  round kept from hiding a dropped save. Declined: a drop all or none across repositories - a save another repository
  took is on its remote and stays, and the board names what was dropped; unmaking a pushed commit would be a second
  write over the remote.

## Measured

A save of four story moves with their twelve issues over three repositories (15 stories, 40 issues per repository),
against local bare repositories served over HTTPS, median of three runs on a loaded machine: 21.7 s and about 808 Git
processes. Of it: 191 single-file reads, about 8 s - most of them the scratch copy of every file of every repository,
the rest the read-back; three commits, about 2.9 s, each running several processes per changed file; unexplained time
between the Git calls, about 2 s before each commit and the first push; the three pushes with their heads, under 1.2 s.
The pushes are not where the time goes; the commit part is.

After the change, the same fixture, three saves: answered in 385, 366 and 306 ms (median 366 ms), the pushes done
about 0.45 s later, the remotes holding every move. With the local commits alone and the reads unchanged it was
6.0 s.

## Plan reading

Read by another model, each claim checked in the code: a planner commit was held by no ref until pushed, so a restart
lost a save already answered - now `refs/planner/local` holds it; the project lock is owned by its thread - now held
only for the local commit, never handed on; every save compared the remote heads - now it compares only the window's
revision; a rejected push could never be pushed again with its old lease - now it is replayed on the fetched head.

## Acceptance

- The measured fixture's save answers in under 1 s, its pushes follow, and the remote holds every move afterwards.
  Seen failing first: today's 21.7 s.
- A planner restarted with a save not yet pushed pushes it.
- A push that conflicts with a change made elsewhere drops the save, and the board's faults name the repository and the
  items; nothing is pushed over the other change.
