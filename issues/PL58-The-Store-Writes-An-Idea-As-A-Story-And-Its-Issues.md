# PL58 — The Store Writes An Idea As A Story And Its Issues

```json
{
  "$schema": "issue-1.json",
  "id": "PL58",
  "title": "The Store Writes An Idea As A Story And Its Issues",
  "state": "Backlog",
  "size": "L",
  "priority": "Medium",
  "rank": 31,
  "blockedBy": [
    "PL46"
  ],
  "covers": "The store writes a checked idea in one save as a story in Idea in the project's repository and an issue in Idea in each repository it names, each with the next free number of that repository's prefix, nothing pushed on a fault before the first push and the pushed repositories named on one after"
}
```

**Size:** L - a new method in the store's interface, the Git store writing several new issues and a new story file
with their numbers and indexes in one save over several repositories, and its failure paths over bare repositories.

**What must be true.** An idea the planner has checked becomes, in one save, a story in Idea in the project's
repository naming an issue in Idea in each repository the idea names, each with the next free number; a save that
cannot finish changes no repository before its first push and names what was pushed after it. The request that
hands the idea to the planner is [PL67](README.md#pl67).

## Plan

- **The interface.** `store/api` gains `NewIdea` - the story's title, gain, test and priority, and a list of
  `NewIssue`, each naming a different repository of the project, each with its `worker` - and
  `PlanningStore.createIdea(Revision, NewIdea)`, answering `NewIdea.Added` with the story's id, the issues' ids and
  the board after the save. Its default throws `UnsupportedOperationException`, as `createIssue`'s does, and
  `MockStore` keeps it. `GitStore.createIdea` refuses, as `NotSavable` and before any file is written, a repository
  outside the project or one named twice, as `GitStore.createIssue` refuses one outside the project.
- **Several new issues.** `GitStore.saved` and `commitAndPush` take a list of new issues where they take one
  `@Nullable NewIssue` today; `IdeaFile.write` writes each in its repository with its head and index row, its number
  the next free one of that repository's prefix; `readsBack` checks each before anything is pushed.
- **The story file.** A new writer beside `IdeaFile` gives the next `ST<n>` after the highest in the own
  repository's `stories/`, without a leading zero and at most `ST99999`, as `StorySchema.ID` requires (`IdeaFile`
  pads issue numbers with `%02d`; a story's must not be). It writes `stories/ST<n>-<words>.md` with a
  `story-2.json` head naming each `<repository>#<id>` and no `state`: with issues a story's state follows theirs,
  and `GitStore.story` takes a written state over them. Its path joins the heads `SaveFiles.changed` commits, which
  commits only the heads and indexes it is given, and `SaveFiles.subject` words the addition; then the stories
  index is written by `StoriesIndex.write` as for a move. A story reference must match `StorySchema.ISSUE`
  (`porelo` repository names), so a project whose repository does not is refused as `NotSavable` by the index's
  faults, and the tests name repositories `porelo-*`.
- **Faults.** Every file, index and commit is built and read back before the first push, so any fault there
  changes no repository: `IdeaFile`'s refusals (the own repository using no prefix or several, a prefix at 999) and
  the index's faults are `NotSavable`; a remote head moved since the last read, or a first push rejected, is
  `StaleRevision`, as `GitStore.saved` and `commitAndPush` answer today. Pushes run in order, the own repository
  last, so the story never stands without its issues; they are not all or none: a push failing after the first is
  `PartlySaved` naming the pushed repositories and the failed one, and the issues pushed stay in Idea without their
  story.
- **Red first**, in `store/git` over local bare repositories: an idea naming two `porelo-*` repositories is read
  back from fresh clones as one story in Idea naming two new issues with the next numbers, and the story's id has
  no leading zero; a repository outside the project, or one named twice, writes nothing; a fault in the second
  repository's files pushes nothing; a failed second push leaves the first pushed and answers `PartlySaved`.

## Acceptance

- Saved over bare repositories, an idea is read back as a story in Idea with an issue in each repository it named,
  each with the next free number; a fault before the first push leaves every repository as it was and names the
  fault; a push failing after another answers `PartlySaved` naming both.
