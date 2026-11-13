# PL94 — Findings Read From The Reader's Committed File

```json
{
  "$schema": "issue-1.json",
  "id": "PL94",
  "title": "Findings Read From The Reader's Committed File",
  "state": "Analyzed",
  "size": "M",
  "priority": "High",
  "rank": 2,
  "blockedBy": ["PL90", "PL93"],
  "covers": "A reading's findings are read from the file the reader committed where its work landed, checked by today's rules for a Findings block, with the answer's block as the fallback while readers do not yet write the file",
  "openQuestions": 2
}
```

**Size:** M - reading a file at a landed commit through the Git store, the check shared with today's answer, and the
judging in `core`; one contract change with the window, a reader's `LANDED`. It reads the directory that
[PL93](README.md#pl93) brings and the landings that [PL90](README.md#pl90) brings.

**What must be true.** After a reader ends and its work has landed, the planner reads
`<directory>/<ISSUE>-reading-<round>.md` at the reader's landed commit, checks its `Findings:` block by the rules the
answer's block has today (numbered rows, blocking or note, file and line), and judges blocking findings as today. A
clean reading writes the file with a `Findings:` line and no row, so a missing file means the reader did not finish its
work, never a clean reading, and the issue waits for the person. While readers do not yet write the file, the answer's
`Findings:` block stays the fallback. The rework's order names the file, which the rework deletes with its fix.

## Plan

What stands today, read in the code:

- A reader is judged once, at its `ENDED`: `WorkRun.report` (`planner/core/.../WorkRun.java:656`) calls
  `WorkEnds.judge` (`WorkRun.java:817`), which for a `READER` that ended done reads the answer's block with
  `WorkEnds.findings` (`WorkEnds.java:200`): exactly one `Findings:` line, through the end of the text, numbered rows
  `<n>. <blocking|note> | <file or -> | <line or -> | <text>`, at most 100 rows and 64 KiB; anything else is
  `INVALID_FINDINGS`. A block with no row is a clean reading (`:216`-`:242`).
- `Ended.landed` (`WorkRun.java:119`) carries where a task's work landed, each a `Landing(branch, ref, remote,
  commit)` (`NextStep.java:115`), copied from Porelo; empty while the work waits at the gate. `LANDED` is taken only for
  a task whose judgment is `Done` (`WorkRun.landed`, `:871`, refused at `:912`-`:919`), so a reader's `LANDED` is
  refused today, and `WorkRunTest.aLandingOfAnUnknownUnendedFailedOrReadingTaskIsRefusedAndOffersNothing` (`:1180`)
  holds that. `planner.yaml` (`:802`-`:806`) describes `LANDED` as a build's.
- The planner's clone of a repository holds only its tracked branch: `GitRemote.fetch` (`GitRemote.java:85`) fetches
  that one ref at depth 1 into `refs/planner/fetched`. `Remote.read` (`GitRemote.java:137`) reads a file at any commit
  the clone holds, so a landed commit must be fetched first, from its `ref`, into a ref of its own.
- `core` reaches the store through `WorkRun.store`, a `PlanningStore` (`WorkRun.java:39`).

The steps:

1. **core, one check.** `WorkEnds.findings` becomes the check of a block in any text, the reader's file or its answer,
   with the same rules and limits; nothing about the rules changes.
2. **store/api.** `PlanningStore.reading(repository, ref, commit, issue, round)` answers the file's text, or none when
   the commit holds no such file; a store that cannot read files (the mock) says so, and `core` then judges by the
   answer alone.
3. **store/git.** `Remote` gains a fetch of one landed `ref` at depth 1 into `refs/planner/landed`, refused unless the
   fetched commit is the reported one; `GitStore.reading` then reads `<directory>/<ISSUE>-reading-<round>.md` with the
   directory of [PL93](README.md#pl93). The tracked branch's ref is never touched. A failed fetch is a store fault.
4. **core, the judging.** A `READER` that ended done with a landing is judged from its file, read at each landed commit
   (several that hold it must agree). A reader that ended without a landing is judged on its `LANDED`, which
   `WorkRun.landed` now takes for a reader once, as it does for a build. A file that stands decides; a malformed one is
   `INVALID_FINDINGS`, never a fallback to the answer. A missing file falls back to the answer's block, and with no
   well-formed block is `INVALID_FINDINGS`, so the issue waits for the person; a store fault waits the same way. The
   round is the one the reader's order named, `Progress.rounds().rounds() + 1` before the count. No new reason reaches
   the window: `INVALID_FINDINGS` already reads "its findings could not be read".
5. **app.** `planner.yaml`'s `taskEvent` says `LANDED` is taken for a reader too; the file is read before the record's
   lock is taken, so a slow fetch holds no other report.
6. The rework's order naming the file and deleting it is [PL93](README.md#pl93)'s rework text; nothing more here.

Tests, red first, by level:

- Unit, `WorkRunTest` over a fake store (the first red one): a reader whose answer says clean but whose file holds a
  blocking finding is judged blocking. Then: a missing file with a well-formed answer counts the answer; a missing file
  with none, a malformed file, and a store fault each need the person and count no reading; a reader ended without a
  landing is judged on its `LANDED` once, and a resend is refused. Harm: blocking findings dropped, a reading passed
  that nobody finished, a reading counted twice.
- Unit, `WorkEndsTest`: a file with two blocks, a row after the block, or a row out of order is refused; `Findings:`
  alone is clean. Harm: a malformed file taken for a clean reading.
- Integration, `GitRemoteTest` over a served repository: a landed ref is fetched and its file read; a ref whose tip is
  not the reported commit is refused; the tracked branch's ref is unchanged. Harm: another commit's findings read, or
  the board's clone moved by a reading.
- Integration, `WorkRunHttpTest` and `ApiMatchesDescriptionTest`: a reader's `LANDED` is taken over HTTP, and the
  description says so. Harm: the window and the planner reading the contract differently.

## Acceptance

- The tests above, each seen red first; the full build, the screen's checks and the documents' checks green.
- A reader whose file and answer disagree is judged by its file; a reader with neither waits for the person.

## Open questions

- When the reader's file is missing but its answer holds a well-formed block, the plan counts the answer, as the
  fallback; does the fallback end, and how: with a project setting, or with a later change of the planner once every
  repository's readers write the file?
- A reader that ends without a landing - its commit at the gate, or no commit at all - is judged on its `LANDED` in the
  plan, so a reader that committed nothing waits for the person: is that right while the fallback stands, or is such a
  reader judged at once by its answer?

## To be checked

- Whether the window sends `LANDED` for a reader's task as it does for a build's; a contract change agreed with the
  window's agent in the channel before this is built.
- Whether a landed `ref` is served by the repository's central, which the planner's clone fetches from, or only by
  Porelo's gate; agreed with Porelo's agent.
- Whether Porelo reports a landing, or a `LANDED`, for a task that pushed no commit.
