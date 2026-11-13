# PL93 — Start Instructions Per Working State From The Store

```json
{
  "$schema": "issue-1.json",
  "id": "PL93",
  "title": "Start Instructions Per Working State From The Store",
  "state": "Implementing",
  "size": "L",
  "priority": "High",
  "rank": 1,
  "blockedBy": [],
  "covers": "Every task order the planner builds carries the start instructions of its working state, given by the store's adapter from the project's settings: where a reader writes its findings and what a task does with findings it finds"
}
```

**Size:** L - the store interface, the Git store's reading and its save (a clean reading's file deleted with the move),
the order building in `core` and the offer's record in `app`.

**Estimate:** 113 min, the measured median of L.

**What must be true.** A task started for a working state is told by its order where its results go and what to do with
findings it finds, as the project's settings say, so readers and reworks across repositories follow one place without a
text fixed in the planner. The Git store gives a reader: "write your reading to
`<directory>/<ISSUE>-reading-<round>.md`, with a `Findings:` block in today's rows, and a `Findings:` line with no
row when the reading is clean", and a rework:
"delete the reading's file in the same commit as your fix". `<directory>` is the project's `readings.directory` in
`project.yml`, by default `readings`: a path
relative to the repository's root, without `..` and without a leading `/`; a malformed one is refused as a fault of the
project, never used.

## Decided

- A clean reading's file is not kept: the planner deletes it when the issue moves on from the reading, in the commit
  that saves that move.
- The directory is Porelo's project-file key `readings: {directory}`, default `readings`; the planner reads that same
  key and adds none of its own. There is no override per repository: the planner reads `readings.directory` from
  `project.yml` only and tells each task its path in the order. A repository's `porelo.yml` is not read for it.

## Plan

What stands today, read in the code:

- Every reader's and every rework's order is built by `NextStep.task` (`planner/core/.../NextStep.java:503`): it
  expands the rule's order through `WorkOrder.prompt` (`WorkOrder.java:456`, placeholders `{story}`, `{issues}` and
  `{tasks}` at `:28`), adds a rework's findings as "Reading and operator words" (`NextStep.java:531`), and appends the
  fixed text `FINDINGS` (`:20`) to a reader's order (`:534`). Its callers are `NextStep.after` (a reader after a build,
  a rework after blocking findings), `NextStep.landed`, and in `WorkRun` the reading choice (`WorkRun.java:1249`) and
  `handReader` (`:1759`).
- Whether a task reads or reworks is no property of a working state: `stories/work.yml` names no role
  (`WorkReader.java:38`). `core` decides it by the flow: the task after a build is a `READER` (`NextStep.java:357`); a
  `BUILDER` started from blocking findings into the rule's `blocked` state is a rework. The plan keys the instructions
  by that role, which is what "per working state" comes to in today's code.
- No reading round reaches `NextStep.task`. The round is `Progress.rounds().rounds()` (`ReadingCycle.Rounds`): a reader
  started now writes round `rounds() + 1`, a rework follows round `rounds()`.
- The store reads `project.yml` only at open, for the repositories (`ProjectFile.repositories`, `ProjectFile.java:30`);
  `stories/Project.read` ignores keys it does not know. Nothing reads `porelo.yml`. `StoreReader.read`
  (`StoreReader.java:64`) reads each repository's part at its commit; `GitStore.board` (`GitStore.java:1410`) joins the
  parts into the `Board`, whose `faults` show what could not be read.
- A move is saved by `GitStore.commitLocally` (`GitStore.java:563`): `SaveFiles.write` copies the commit's planning
  files into the save's scratch directory, the edits change them there, `SaveFiles.changed` lists what differs from the
  commit, a file in `before` that is gone counting as deleted (`SaveFiles.java:91`), and `GitRemote.commit` builds the
  commit from blobs in the bare clone, removing a missing path from the index. No checkout is ever written.
  `SaveFiles.subject` adds `[skip ci]` only when every path is under `issues`, `stories` or `stats` (`:177`).

The steps:

1. **store/api.** A record `Readings(String path, String reading, String rework)` per repository in `Board`: `path` is
   the store's template `<directory>/{issue}-reading-{round}.md`, the two texts the instructions above with `{path}`.
   `Writes` gains `cleared`, issue to path: the reading files a save deletes.
2. **store/git, the directory.** `StoreReader.read` reads the project file's `readings.directory` in the own
   repository's part. The value passes `RepoPath.checked` (`RepoPath.java:22`:
   `[A-Za-z0-9._-]` segments, no `..`, no leading `/`), and each segment is refused when it is a Windows device name
   (`CON`, `PRN`, `AUX`, `NUL`, `COM0`-`COM9`, `LPT0`-`LPT9`, in any case, with or without an extension) or ends in a
   `.`, so the path names one file on every system. Every repository of the project gets the same directory.
3. **core, the order.** `NextStep.task` takes the round, fills the path, and adds the reading text to a `READER`'s
   order after `FINDINGS` and the rework text to a rework's. A repository with no `Readings` refuses the task, so its
   issue needs the person (`NEXT_STEP_UNAVAILABLE`, `NextStep.java:372`, `WorkRun.java:1269`).
4. **core and app, the path kept.** The filled path and the round are saved with the offer (`WorkRun.Offer`, kept in
   the local record by `WorkRecord`), never computed again: a refreshed board, a resent report or a later round never
   moves an existing task's reading or deletion. A rework's order and offer take the path saved with the reader it
   follows.
5. **The clean reading's file deleted with the move.** When a clean reading moves its issue on (`NextStep.after`, the
   non-blocking branch, target `rule.done()`), the move's `Writes.cleared` carries the reader's saved path.
   `GitStore.commitLocally` takes a cleared path only when it is that issue's saved shape in that issue's repository,
   reads it into `before` and leaves it out of the scratch directory, so `SaveFiles.changed` lists it and
   `GitRemote.commit` removes it in the same commit as the move. A path the commit does not hold as a plain file, at
   exactly that spelling, deletes nothing. `SaveFiles.subject` counts the readings directory as planning, so the
   commit still carries `[skip ci]`. This is the only deletion the planner makes.

Tests, red first, by level:

- Unit, `NextStepTest` (the first red one): a reader of round 1 of `AA1` is told `readings/AA1-reading-1.md`, after one
  more round round 2; a rework is told to delete the file its reader was told; a repository without `Readings` gets no
  task and needs the person. Harm: a reader writing where the planner never reads, or a stale file taken for a later
  round's.
- Unit, `WorkRunTest`: a board refreshed with another directory leaves a started reader's and its rework's path as
  saved; a clean reading's move carries exactly that path in `cleared`. Harm: a reading judged or deleted at a place
  the task never wrote.
- Unit, `StoreReaderTest` over its fake remote: the default, the project's value, and `../x`, `/x`, `a b`, `CON`,
  `nul.md`, `x.` and a number refused as faults. Harm: a reader told to write outside its repository.
- Unit, `GitStoreSaveTest`: a move with a cleared path removes that file and nothing else, in the move's commit, with
  `[skip ci]`; a cleared path of another issue, another repository or another shape is refused; a path the commit does
  not hold changes nothing. Harm: the planner deleting a file it does not own.

## Acceptance

- The tests above, each seen red first; the full build and the documents' checks green.
- A reader's and a rework's order name the reading round's file in the project's directory; a clean reading's move
  deletes that file in its own commit.

## To be checked

- Whether a repository's own checks (`check-readmes`, `check-doc-site`, `check-citations`) trip on a Markdown file
  under `readings/`.
- Whether a reader's landed commit has reached the tracked branch when its issue moves on; if not, the move deletes
  nothing and the file arrives later.
