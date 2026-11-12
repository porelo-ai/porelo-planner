# PL100 — A Story File Without A Title Is Read

```json
{
  "$schema": "issue-1.json",
  "id": "PL100",
  "title": "A Story File Without A Title Is Read",
  "state": "Implementing",
  "size": "S",
  "priority": "High",
  "rank": 21,
  "blockedBy": [],
  "covers": "A story file named `ST<n>.md`, which the work format allows beside `ST<n>-<Title>.md`, is read onto the board and seen as added, as the stories index already takes it"
}
```

**Size:** S - two patterns in `store/git` and their tests, no contract.

**Estimate:** 33 min, the measured median of S.

**What must be true.** The work format names a story file `ST<n>.md` or `ST<n>-<Title>.md`. The planner reads both
onto the board. Today the stories index takes both, but the board reads only the form with a title, so a story saved
as `ST<n>.md` stands in the index and is missing from the board, without a word.

## Plan

- **Today, read in the code.** In `planner/store/git`:
  - `StoriesIndex.FILE` takes `(ST[0-9]+)(-[^/]*)?\.md`.
  - `StoreReader.STORY_FILE` takes only `ST[0-9]{1,5}-[^/]*\.md`, so the board skips the bare form.
  - `GitStore.ADDED` takes only `stories/(ST[0-9]+)-[^/]*\.md`, so a story added in the bare form is not seen as
    added.
- **The change.** Both patterns take the title part as optional, as `StoriesIndex` does. The other readers of story
  file names in `store/git` are read once and made to agree.
- **Tests, red first, unit level.**
  - `StoreReaderTest`: a store holding `stories/ST7.md` lists ST7 on the board.
  - `GitStore`'s test of added files: `stories/ST7.md` is seen as added.
  - Harm: a story the index lists, missing from the board.

## Acceptance

- A story file `ST<n>.md` is on the board and seen as added; `ST<n>-<Title>.md` is read as today; each new test seen
  red; the `store/git` and app suites pass.
