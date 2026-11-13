# PL93 — Start Instructions Per Working State From The Store

```json
{
  "$schema": "issue-1.json",
  "id": "PL93",
  "title": "Start Instructions Per Working State From The Store",
  "state": "Analyzed",
  "size": "M",
  "priority": "High",
  "rank": 1,
  "blockedBy": [],
  "covers": "Every task order the planner builds carries the start instructions of its working state, given by the store's adapter from the project's settings: where a reader writes its findings and what a task does with findings it finds",
  "openQuestions": 1
}
```

**Size:** M - the store interface, the Git store and the order building in `core`, with no change to `planner.yaml`;
two file formats other repositories own or read, `project.yml` and Porelo's `porelo.yml`, gain a key.

**What must be true.** A task started for a working state is told by its order where its results go and what to do with
findings it finds, as the project's settings say, so readers and reworks across repositories follow one place without a
text fixed in the planner. The Git store gives a reader: "write your reading to
`<directory>/<ISSUE>-reading-<round>.md`, with a `Findings:` block in today's rows, and a `Findings:` line with no
row when the reading is clean", and a rework:
"delete the reading's file in the same commit as your fix". `<directory>` is the project's `readings.directory` in
`project.yml`, overridden by a repository's own `readings.directory` in its `porelo.yml`, by default `readings`: a path
relative to the repository's root, without `..` and without a leading `/`; a malformed one is refused as a fault of the
project, never used.

## Plan

What stands today, read in the code:

- Every reader's and every rework's order is built by `NextStep.task` (`planner/core/.../NextStep.java:503`): it
  expands the rule's order through `WorkOrder.prompt` (`WorkOrder.java:456`, placeholders `{story}`, `{issues}` and
  `{tasks}` at `:28`), adds a rework's findings as "Reading and operator words" (`NextStep.java:531`), and appends the
  fixed text `FINDINGS` (`:20`) to a reader's order (`:534`). Its callers are `NextStep.after` (a reader after a build,
  a rework after blocking findings), `NextStep.landed`, and in `WorkRun` the reading choice (`WorkRun.java:1249`,
  `READER` or `BUILDER`) and `handReader` (`:1759`). A MAIN or BUILDER started by hand gets its order in `WorkRun`
  (`:574`).
- Whether a task reads or reworks is not a property of a working state: `stories/work.yml` names no role
  (`WorkReader.java:38`). `core` decides it by the flow: the task after a build is a `READER` (`NextStep.java:357`); a
  `BUILDER` started from blocking findings into the rule's `blocked` state is a rework. The plan keys the instructions
  by that role, which is what "per working state" comes to in today's code.
- No reading round reaches `NextStep.task`. The round is `Progress.rounds().rounds()` (`ReadingCycle.Rounds`), counted
  by `ReadingCycle.count` when a reading is judged: a reader started now writes round `rounds() + 1`, a rework follows
  round `rounds()`.
- The store reads `project.yml` only at open, for the repositories (`ProjectFile.repositories`, `ProjectFile.java:30`,
  parsed by `stories/Project.read`, which ignores keys it does not know). Nothing in the planner reads `porelo.yml`.
  `StoreReader.read` (`StoreReader.java:64`) reads each repository's part at its commit through `Remote.read`;
  `GitStore.board` (`GitStore.java:1410`) joins the parts into the `Board`, whose `faults` show what could not be read.
  `RepoPath.checked` (`RepoPath.java:22`) already refuses `..`, a leading `/` and any character beyond
  `[A-Za-z0-9._-]`.

The steps:

1. **store/api.** A record `StartInstructions(String reading, String rework)`: the store's own text, with `{issue}` and
   `{round}` for `core` to fill. `Board` gains `Map<String, StartInstructions> instructions`, by repository; the
   existing constructors give none, so the mock store and other callers stay as they are.
2. **store/git.** `StoreReader.read` reads the own repository's `project.yml` and each repository's `porelo.yml` at the
   part's commit; `Part` keeps the repository's `readings.directory`, or the fault that refused it. The directory is
   the repository's own value, else the project's, else `readings`, checked by `RepoPath.checked` and refused when it
   is no string. `GitStore.board` fills `Board.instructions` with the two texts above for every repository whose
   directory stands, and adds a fault naming the file and the key for one that does not.
3. **core.** `NextStep.task` takes the reading round. A `READER` order gets the reading text after `FINDINGS`, a rework
   (a `BUILDER` built from findings: `NextStep.after`'s blocking branch and `WorkRun`'s `REWORK` choice) the rework
   text, each filled with the issue and the round. A repository with no instructions refuses the task, so its issue
   needs the person (`NEXT_STEP_UNAVAILABLE`), as for any task that cannot be built (`NextStep.java:372`,
   `WorkRun.java:1269`): no reader starts without the place the planner reads. Other orders are unchanged.

Tests, red first, by level:

- Unit, `NextStepTest` (the first red one): a reader of round 1 of `AA1` is told `readings/AA1-reading-1.md`, and
  after one more round, round 2; a rework after the blocking reading of round 1 is told to delete that file; a
  repository without instructions gets no task and its issue needs the person. Harm: a reader writing where the planner
  never reads, a stale file taken for a later round's, or a reader started with no place at all.
- Unit, `StoreReaderTest` over its fake remote: the default, the project's value, a repository's override, and `../x`,
  `/x`, `a b` and a number each refused as a board fault with no instructions for that repository. Harm: a reader told
  to write outside its repository, or where another repository's setting points.
- Unit, `GitStoreReadTest`: `Board.instructions` follows a changed `porelo.yml` on the next refresh. Harm: an order
  naming a directory the project no longer uses.

## Acceptance

- The tests above, each seen red first; the full build and the documents' checks green.
- A reader's and a rework's order, as the planner offers them, name the reading round's file in the project's
  directory; a project with a malformed directory shows the fault on the board and starts no reader there.

## Open questions

- A clean reading's file is deleted by no rework, so `readings/` keeps one file per clean reading: does it stay for
  good as the record of the reading, or is it deleted when the issue moves on, and by whom?

## To be checked

- Whether Porelo accepts an unknown key `readings` in `porelo.yml` or refuses it; `porelo.yml` is Porelo's format, so
  the key needs agreement with its agent in the channel before this is built.
- Whether another repository's tools (the shared tools, Porelo's project setup) read `project.yml` strictly, so a new
  top-level `readings` needs agreement there too.
- Whether a repository's own checks (`check-readmes`, `check-doc-site`, `check-citations`) trip on a Markdown file
  under `readings/`.
- Whether a reader's task may push a commit at all, since Porelo's gate gives it the work it reads read only.
