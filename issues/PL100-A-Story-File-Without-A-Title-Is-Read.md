# PL100 — A Story File Without A Title Is Read

```json
{
  "$schema": "issue-1.json",
  "id": "PL100",
  "title": "A Story File Without A Title Is Read",
  "state": "Review",
  "size": "S",
  "priority": "High",
  "rank": 21,
  "blockedBy": [],
  "covers": "A story file named `ST<n>.md`, which the work format allows beside `ST<n>-<Title>.md`, is read onto the board and seen as added, as the stories index already takes it"
}
```

**Size:** S - three patterns in `store/git` and their tests, no contract.

**Estimate:** 33 min, the measured median of S.

**What must be true.** The work format names a story file `ST<n>.md` or `ST<n>-<Title>.md`. The planner reads both
onto the board. Today the stories index takes both, but the board reads only the form with a title, so a story saved
as `ST<n>.md` stands in the index and is missing from the board, without a word.

## Plan

- **Today, read in the code.** In `planner/store/git`, `StoriesIndex.FILE`, `StoryFile.FILE` and
  `StoreReader.GENERATED` take `ST<n>(-<Title>)?.md`; three readers took only the titled form:
  - `StoreReader.STORY_FILE`, so the board skipped the bare form;
  - `GitStore.ADDED`, so a dropped save's story added in the bare form was told by its path, not its id;
  - `LocalSaves.NUMBERED`, so a story added in the bare form on the remote beside one added here under the same
    number was not seen as a clash, and the save was made again beside it.
- **The change.** The three take the title part as optional, as `StoriesIndex` does; `LocalSaves.NUMBERED` takes
  only `ST<n>` in `stories/`, as a story's id is. `GitStore.dropped` is package-private, for its test. The other
  readers of story file names in `store/git`, `store/api`, `core` and `app` take ids, not file names, or already
  take both forms.
- **Tests, red first, unit level.**
  - `StoreReaderTest`: a store holding `stories/ST7.md` and `stories/ST8-Titled.md` reads both onto the board, each
    with its file. Harm: a story the index lists, missing from the board.
  - `GitStoreTest`: a dropped save that added `stories/ST7.md` names ST7. Harm: a lost story told by a path.
  - `LocalSavesTest`: a save adding `stories/ST2-Mine.md` over a remote that added `stories/ST2.md` is dropped.
    Harm: two stories under one number.

## Acceptance

- A story file `ST<n>.md` is on the board and seen as added; `ST<n>-<Title>.md` is read as today; each new test seen
  red; the `store/git` and app suites pass.
