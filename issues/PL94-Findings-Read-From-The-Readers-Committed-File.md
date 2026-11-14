# PL94 — Findings Read From The Reader's Committed File

```json
{
  "$schema": "issue-1.json",
  "id": "PL94",
  "title": "Findings Read From The Reader's Committed File",
  "state": "Backlog",
  "size": "L",
  "priority": "Medium",
  "rank": 2,
  "blockedBy": ["PL93"],
  "covers": "A reading's findings are read from the file the reader committed where its work landed, checked by today's rules for a Findings block, with the answer's block as the fallback while readers do not yet write the file"
}
```

**Size:** L - fetching and comparing landed commits in the Git store, the shared check, judging and its races in
`core`, the offer's record in `app`, and `planner.yaml`'s `LANDED` for a reader; it reads the path that
[PL93](README.md#pl93) saves with the offer and the landings a reader's order names.

**To be rethought.** Readings will not be files in a repository: findings will go through the forge's review by
the forge adapters, and a rework will take them from there. Until then a reader's `Findings:` block in its answer
stays the way findings reach the planner. This issue is rethought on that ground before it is built.

**What must be true.** After a reader ends and its work has landed, the planner reads
`<directory>/<ISSUE>-reading-<round>.md` at the reader's landed commit, checks its `Findings:` block by the rules the
answer's block has today (numbered rows, blocking or note, file and line), and judges blocking findings as today. A
clean reading writes the file with a `Findings:` line and no row, so a missing file means the reader did not finish its
work, never a clean reading, and the issue waits for the person. While readers do not yet write the file, the answer's
`Findings:` block stays the fallback. The rework's order names the file, which the rework deletes with its fix.

## Decided

- The answer's block stays the fallback until every repository's readers write the file; a later change of the
  planner removes it then. There is no project setting.
- A reader that pushed nothing - its commit still at the gate, or none - is judged at once by its answer, without
  waiting for the person.
- Porelo's gate records a landing per task, whatever its role, so a reader's pushed file shows as its landing; the
  window already sends `LANDED` for any task of an offer.

## Plan

What stands today, read in the code:

- A reader is judged once, at its `ENDED`: `WorkRun.report` (`planner/core/.../WorkRun.java:656`) calls
  `WorkEnds.judge` (`:817`), which for a `READER` that ended done reads the answer's block with `WorkEnds.findings`
  (`WorkEnds.java:200`): one `Findings:` line, through the end of the text, numbered rows `<n>. <blocking|note> |
  <file or -> | <line or -> | <text>`, at most 100 rows and 64 KiB; anything else is `INVALID_FINDINGS`.
- `Ended.landed` (`WorkRun.java:119`) carries where a task's work landed, each a `Landing(branch, ref, remote,
  commit)` (`NextStep.java:115`), copied from Porelo, empty while the work waits at the gate. `WorkRun.landed` (`:871`)
  refuses `LANDED` for a task whose end is not `Done` (`:912`-`:919`), so a reader's `LANDED` is refused today, and
  `WorkRunTest.aLandingOfAnUnknownUnendedFailedOrReadingTaskIsRefusedAndOffersNothing` (`:1180`) holds that;
  `planner.yaml` (`:802`-`:806`) describes `LANDED` as a build's.
- `GitRemote.fetch` (`GitRemote.java:85`) fetches one branch from the repository's own clone URL, the central of
  `project.yml`, at depth 1 into `refs/planner/fetched`. `Remote.read` (`:137`) reads a file at a commit the clone
  holds, checking its mode and size from `git ls-tree -l` before it reads the blob (`refused`, `:438`), but decodes the
  bytes leniently (`text`, `:558`).
- A report is checked against its offer and judged inside `record.locked` (`WorkRun.java:656`, `:871`); an identical
  report replays its disposition by its fingerprint (`WorkRun.fingerprint`, `:2430`).

The steps:

1. **core, one check.** `WorkEnds.findings` becomes the check of a block in any text, the reader's file or its answer,
   with the same rules and limits.
2. **store/api.** `PlanningStore.reading(repository, landing, built, path)` answers the reader's file, none when it has
   none, or a refusal; `landing` is the reader's landed ref and commit, `built` the commits it read, `path` the one
   saved with its offer by [PL93](README.md#pl93). A store that reads no files (the mock) answers none.
3. **store/git, where it fetches.** Only from the repository's configured central (`Repository.url`); a landing's
   `ref` is only ever the source of the refspec, checked as a `refs/` name by `planner.yaml`'s `RefName` rules, and its
   `remote` and `branch` are never used. The destination is the planner's own `refs/planner/landed`; the tracked
   branch's ref is never touched, and every read is by the verified commit id: a fetched tip other than the reported
   commit is refused.
4. **store/git, whose file it is.** The commits the reader read (its offer's `Build`) are fetched the same way. The file
   counts as the reader's only when the reader's landed commit holds it at exactly the saved path as a plain file,
   every tree on the way a tree, and no commit it read holds it: a file inherited from the build, or from anything
   before the reader, is refused. With the landing reported by Porelo for this offer's bound task and container, that
   ties the file to the reader task, repository, issue, round and the build it read.
5. **store/git, its size.** The file's size from `ls-tree -l` is checked against `WorkEnds.MOST_ANSWER` before the
   blob is read, and its bytes are decoded strictly as UTF-8; an oversized or undecodable file is refused.
6. **core, the judging.** At `ENDED`, a reader that ended done with a landing is judged from its file; one without a
   landing is judged at once by its answer, as decided. With a landing:
   - a refused file, a malformed block, or a store fault is `INVALID_FINDINGS`, never a fallback to the answer;
   - a file that stands decides, unless the answer holds a `Findings:` line whose block is malformed or differs from
     the file's: then the reading stops as `INVALID_FINDINGS`, so neither side can drop the other's blocking findings;
   - a missing file falls back to the answer's block, and with none well-formed is `INVALID_FINDINGS`.
   No new reason reaches the window: `INVALID_FINDINGS` already reads "its findings could not be read".
7. **core, the races.** Before fetching, outside the lock, the report is matched to its offer: the bound task and
   container, ended, not void, its issue not yet judged for that round, the run neither stopped nor halted; a report
   whose fingerprint was already taken replays without a fetch. Under the record's lock the same is checked again
   before the judgment is saved, so a cancellation, a replacement or a concurrent report never applies a stale file or
   counts a reading twice.
8. **app.** A reader's `LANDED` is taken and kept on its end record, judging nothing again; `planner.yaml`'s
   `taskEvent` says so.

Tests, red first, by level:

- Unit, `WorkRunTest` over a fake store (the first red one): a reader whose answer says clean and whose file holds a
  blocking finding stops; so does the reverse. Then: a file alone decides; a missing file with a well-formed answer
  counts the answer; a reader with no landing is judged by its answer; a refused, oversized or malformed file, and a
  store fault, each stop with no fallback; a void offer or a stopped run applies nothing; a resent report counts once.
  Harm: blocking findings dropped, a reading nobody finished passed, a reading counted twice or applied after a stop.
- Unit, `WorkEndsTest`: a text with two blocks, a row after the block, or a row out of order is refused; `Findings:`
  alone is clean. Harm: a malformed file taken for a clean reading.
- Integration, `GitRemoteTest` over a served repository: the reader's file is read at its landed commit; a file the
  build already held, a tip other than the reported commit, a file over the limit, a non-plain entry and a path
  differing in case are refused; the tracked branch's ref is unchanged. Harm: another commit's or another task's
  findings read, or the board's clone moved by a reading.
- Integration, `WorkRunHttpTest` and `ApiMatchesDescriptionTest`: a reader's `LANDED` is taken over HTTP and judges
  nothing again. Harm: the window and the planner reading the contract differently.

## Not taken

- **A fallback chosen before the task starts.** As decided, the answer is the fallback for every reader until a later
  change removes it, so the policy is fixed for all, not per task; a reader that leaves the file out gets no more than
  one that writes a clean file, since both are its own words, and a file that stands is compared with the answer.
- **Stopping a rework from deleting another issue's file.** The planner cannot refuse a commit an agent pushes; only
  Porelo's gate could. No judgment reads a reading file on the tracked branch - each is read at its reader's own
  landed commit - so a rework deleting another file changes no judgment; the planner's own deletion is held to the
  saved path in `GitStore.commitLocally` by [PL93](README.md#pl93).

- **Where a landing is fetched from, agreed with Porelo.** A landing is Porelo's gate forwarding the task's work to a
  branch on the repository's central; the row's landed list names the branch and the commit. The planner fetches that
  commit from the central with the person's login. If the branch has moved on, the named commit is still the one
  read; if the central dropped it after a force-push, the fetch fails and the reading stops for the person, never
  read as clean.

## Acceptance

- The tests above, each seen red first; the full build, the screen's checks and the documents' checks green.
- A reader whose file and answer disagree stops for the person; a reader with no landing is judged by its answer.

