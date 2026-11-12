# PL93 — Start Instructions Per Working State From The Store

```json
{
  "$schema": "issue-1.json",
  "id": "PL93",
  "title": "Start Instructions Per Working State From The Store",
  "state": "Accepted",
  "size": "M",
  "priority": "High",
  "rank": 1,
  "blockedBy": [],
  "covers": "Every task order the planner builds carries the start instructions of its working state, given by the store's adapter from the project's settings: where a reader writes its findings and what a task does with findings it finds"
}
```

**Size:** M - the store interface, the Git store and the order building in `core`; no change to the window's contract,
since the order it already receives carries the instructions.

**What must be true.** A task started for a working state is told by its order where its results go and what to do with
findings it finds, as the project's settings say, so readers and reworks across repositories follow one place without a
text fixed in the planner. The Git store gives a reader: "write your reading to `<directory>/<ISSUE>-reading-<round>.md`,
with a `Findings:` block in today's rows, and a `Findings:` line with no row when the reading is clean", and a rework:
"delete the reading's file in the same commit as your fix". `<directory>` is the project's `readings.directory` in
`project.yml`, overridden by a repository's own `readings.directory` in its `porelo.yml`, by default `readings`: a path
relative to the repository's root, without `..` and without a leading `/`; a malformed one is refused as a fault of the
project, never used.
