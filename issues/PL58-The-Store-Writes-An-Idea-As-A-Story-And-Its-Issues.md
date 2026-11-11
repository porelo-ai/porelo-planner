# PL58 — The Store Writes An Idea As A Story And Its Issues

```json
{
  "$schema": "issue-1.json",
  "id": "PL58",
  "title": "The Store Writes An Idea As A Story And Its Issues",
  "state": "Review",
  "size": "L",
  "priority": "Medium",
  "rank": 31,
  "blockedBy": [],
  "covers": "The store writes a checked idea in one save as a story in Idea in the project's repository and an issue in Idea in each repository it names, each with the next free number; nothing stands on a fault before the answer, and a push that fails or is dropped after it is named in the board's faults"
}
```

**Size:** L - a new method in the store's interface, the Git store writing several new issues and a new story file
with their numbers and indexes in one save over several repositories, and its failure paths over bare repositories.

**What must be true.** An idea the planner has checked becomes, in one save, a story in Idea in the project's
repository naming an issue in Idea in each repository the idea names, each with the next free number; a save that
cannot be made changes no repository, and one whose push later fails or is dropped is named on the board. The
request that hands the idea to the planner is [PL67](README.md#pl67).

## Plan

- **The interface.** `store/api` gains `NewIdea` - the story's title, gain, test and priority, and a list of
  `NewIssue`, each naming a repository of the project with its `worker` - and
  `PlanningStore.createIdea(Revision, NewIdea)`, answering `NewIdea.Added` with the story's id, the issues' ids in
  the idea's order and the board after the save. Its default throws `UnsupportedOperationException`, as
  `createIssue`'s does, and `MockStore` keeps it; no HTTP call serves it yet, so the OpenAPI contract and the mock
  mode do not change. `GitStore.createIdea` refuses, as `NotSavable` and before a save begins to write, a
  repository outside the project or one named twice.
- **Several new issues.** `GitStore.saved`, `committed` and `commitLocally` take a list of new issues where they
  took one; `Edits` carries the list and the idea whose story is added, and the ids come back by repository.
  `IdeaFile.write` writes each issue in its repository with its head and index row, its number the next free one of
  that repository's prefix; `readsBack` checks each in its repository's new part. `IdeaFile` refuses a title holding
  a line break or a control character, since the heading repeats the title unquoted and a line break there could
  put a forged head above the real one.
- **The story file.** `StoryFile`, beside `IdeaFile`, gives the next `ST<n>` after the highest number a file name
  in the own repository's `stories/` or an anchor row of its `stories/README.md` holds (a finished story may live on
  only in its row), without a leading zero; past `ST99999` it refuses as `NotSavable`. It writes
  `stories/ST<n>-<words>.md`, checked by `RepoPath.checked` with `IdeaFile.words`, with the head
  `stories.StoryHead` writes: `story-2.json`, every free text quoted by Jackson, each `<repository>#<id>`, and no
  `state` (with issues a story's state follows theirs; an idea with no issue states `Idea`). Each reference is
  checked by `StoryHead.reference` against `StorySchema`'s pattern before anything is committed. The story's path
  joins the heads `SaveFiles.changed` commits; the stories index is then written once by `StoriesIndex.write`, as
  for a move, and its faults refuse the save as `NotSavable`. `SaveFiles.subject` words it `Add an idea`.
- **Read back before anything stands.** The story's file as the index completed it reads back through
  `StoryHead.read` equal to the head written, so a quote, colon, `---` or backslash comes back as given and a line
  break is refused. After the commits, the own repository's new part reads the story at its path with each
  reference, and each new issue reads back in its repository's new part; only then does `LocalSaves.record` make
  them stand, all or none.
- **The own repository.** An idea naming the own repository writes the story and that issue in its one commit;
  its issues index and its stories index are each written once.
- **Faults.** Before the answer: a refusal of a file, an index or a reference is `NotSavable`, a file that cannot be
  read or does not read back is `Unreadable`, and a board moved since the revision, or a repository moved before
  `record`, is `StaleRevision`; two ideas at once are kept apart by the project's lock and `record`'s parent check.
  The answer means saved here. The pushes follow in the background, the own repository last; a push that fails
  stays and is named in the board's faults, and one rejected over a change to the same file, or over an issue or a
  story added on both sides, is dropped with the own repository's waiting save. `LocalSaves` drops a story file
  added on both sides as it drops an issue file, since both may carry the number the other took. The drop's fault
  names each item by its file, a new story or issue by its file's name once the board no longer holds it.
- **Accepted.** An issue pushed in another repository before the own repository's save is dropped stays there as
  an ordinary issue in Idea, with no link to the dropped story. A request that timed out and is sent again after its
  save stood makes a second idea; there is no key that would tell the two apart.
- **Tests**, in `store/git` over repositories served over HTTPS: an idea is read back from fresh clones as a story
  in Idea naming its new issue, its id without a leading zero; an idea naming the own repository writes its story
  and its issue there in one commit; a number only an index row holds is not given again; past `ST99999`, a line
  break in the story's title or an issue title that would add a head is refused and nothing stands; a repository
  outside the project or named twice is refused before a save writes; quotes, colons, rules and backslashes read
  back as written; a dropped idea is named by its story beside a move dropped with it; `LocalSaves` drops a save
  that added a story when the remote added another; `StoryHead` reads back what it wrote and refuses a reference
  the schema does not take.

A replay is dropped only when both sides added a story's or an issue's file, whose name carries a number; a file of
another name added on the remote leaves the save to be made again. A story title with a line break is refused before
anything is written, by the heading's check, and the refusal does not carry the break on; no check of its own was
added, since none showed a title passing.

## Acceptance

- Saved over served repositories, an idea is read back from fresh clones as a story in Idea with an issue in each
  repository it named, each with the next free number, and the index checks accept what was pushed.
- A refusal before the answer leaves every repository, here and on its remote, as it was.
- A save dropped after the answer is named in the board's faults by its story.
