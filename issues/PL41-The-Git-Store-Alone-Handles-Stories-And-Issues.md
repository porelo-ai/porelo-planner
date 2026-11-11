# PL41 — The Git Store Alone Handles Stories And Issues

```json
{
  "$schema": "issue-1.json",
  "id": "PL41",
  "title": "The Git Store Alone Handles Stories And Issues",
  "state": "Implementing",
  "size": "L",
  "priority": "High",
  "rank": 23,
  "blockedBy": [],
  "covers": "Stories and issues kept as files in Git are read, checked, indexed and written by the planner's Git store alone - the shared stories library's code taken into store/git, a broken head and a broken issue link shown as faults on the board, a new idea written through the planner, every repository's issues checked against the project's one workflow, and each issue's stages recorded so the project's velocity goes to an estimate - so the build tools can drop the library and their story, issue, stage and link commands"
}
```

**Size:** L - six pieces of this repository, below, weighed together by the code they share; dropping the library,
the story and issue commands, `stage` and the issue part of `check-citations` from the build tools, and the checks
from every build, is other repositories' work.

**What must be true.** In a repository whose build has no story or issue tooling, the planner reads, checks,
indexes and writes stories and issues kept as files in Git by itself: an issue's state changed and an idea added
through the planner write the file, its head and the index; a broken head or a broken issue link shows as a fault
on the board; each issue's stages are recorded, and the project's velocity reaches the task that estimates.

## Decided

- The issue checks, the issue links that `check-citations` reads among them, belong to the store adapter; whether a
  store checks at all is its own implementation's choice.
- Recording an issue's stages and the project's velocity belong to the store adapter too: `stage` and `stats/`
  leave the build tools; with a store kept in an outside tool, that tool keeps them. The planner hands the
  project's velocity to the task when it starts an estimate.
- Until this issue is done, the stories library's code may stand both here and in the build tools.
- The library's code moves as it is; taking the central's host and link pattern from `project.yml` and reading a
  project's repositories from any clone URL is its own issue ([PL42](README.md#pl42)).
- A repository's own `issues/workflow.yml` goes; every repository's issues are checked against the project's
  `stories/workflow.yml`.
- Which issue prefixes a repository owns stays in `project.yml` (`repositories[].issues`): they mark the project,
  not only its issues, and the Git store reads them from there.

## Plan

Today `store/git` reads and writes through the published `porelo-stories` library: `GitStore`, `StoreReader`,
`ProjectFile` and `Part` use `Project`, `Story`, `StoriesIndex`, `Workflow`, `IssueHead`, `IssueIndex`,
`IssueIndexes`, `IssuesIndex` and `IssueSource`. The library is about 3600 lines of main code with three JSON
schemas and its native metadata, and about 3000 lines of tests. `StoreReader` checks a repository's issues against
its own `issues/workflow.yml`, else its own `stories/workflow.yml`. A broken head fails the whole reading, and
`Board.faults` carries only the workflow's faults. Nothing writes a new issue, and a move of an issue in a
repository with `stats/seed.csv` is refused before anything is written. In the build tools, `stage` is about 1050
lines with its tokens and as many of tests; the issue-link check of `check-citations` about 600.

1. **The library's code taken into `store/git`** (S, a move with a package renamed). The classes, schemas, reachability metadata and tests move
   unchanged in content to a package of `store/git` that is not exported; the dependency on `porelo-stories` goes,
   and with it the rule in `AGENTS.md` and `doc/` that the index is rewritten by that library. No behavior
   changes, so no test goes red first: the moved tests and the store's own tests prove the move, on the JVM and in
   the native executable. About 3600 lines of code and 3000 of tests to move may take longer than the median of
   an S.
2. **One workflow for every repository's issues** (XS, one reading in `StoreReader`). `StoreReader` reads the project's `stories/workflow.yml`
   once and checks every repository's issues against it; a repository's own `issues/workflow.yml` is no longer
   read. Red first: a repository whose own `issues/workflow.yml` names a state the project's lacks has that issue
   faulted. Unit tests.
3. **A story takes the state it is given** - done: the store keeps a story's stated state and the library's index
   warns where its issues give another; it moves with piece 1.
4. **A broken head is a fault on the board** - done: an unreadable story or issue file, a duplicate id, an unknown
   state and an unreadable index are faults on the board and the rest is served.
5. **A broken issue link is a fault on the board** (M, a reading taken over with its tests). The store reads how issue pages name an issue - every
   number a link to its row in an index, every row anchored, every link reaching an anchor - and names each
   broken one in `Board.faults` with its file and line. Red first: an issue page linking a number whose index row
   has no anchor shows one fault naming the page and line. Unit tests over files.
   Already a fault, and not part of this piece: a story naming an issue that is not in the project's repositories.
   The issue-link part of `check-citations`, about 600 lines, is taken over with its tests.
6. **An idea added through the planner** (M, a write the save does not have yet, the contract and the screen's
   form). A new call writes an issue file with its head in state Idea under
   the repository's `issues/`, with the next free number of a prefix the repository owns, rewrites the index and
   commits and pushes as a move does. A change to the window's contract and the screen's form. Red first: an
   idea added is read back from a fresh clone with its head, its index row and its number. Unit and store tests
   over a local bare repository.
7. **An issue's stages recorded** (M). A move writes the issue's stage line in `stats/<ID>.jsonl` in the record's
   own format, also for an issue without a record yet, keeping a session's sources private as the record requires,
   and the store answers the project's velocity, the median minutes by size. `stage` is about 1050 lines with its
   tokens, taken over with its tests. The planner starts no estimate today, so handing the velocity to an estimate
   comes with the step that starts one. It covers the stage line of a moved issue, which was asked on its own before
   ([PL22](README.md#pl22)). Red first: a move of an issue without a record writes a stage line that the record's
   reader takes back. Unit tests over files.

8. **An issue settled when it is Finished** (S, on piece 5's reading of the links). A person sets an issue Finished, and that is always taken: the
   Git store settles it in the same commit, deleting its file and keeping its row in the index. Any mention of the
   number left outside `issues/`, or a link to the file, shows as a fault on the board naming each place, on that
   reading and every one after. Red first: setting Finished an issue whose number a document still names commits
   the file's deletion with its row kept, and the next board names the document and line as a fault. Unit and store
   tests over a local bare repository.

Order: 1, 2, 5, 8, 6, 7. Steps 5 to 8 change what the window is answered and are agreed before they are built.
Sum: S 33 + XS 15 (a guess; no XS is measured yet) + M 67 + S 33 + M 67 + M 67 = 282 minutes.

## The checks that leave, and when the Git store runs them

All of them live in `store/git`. A reading of the board names what it finds as a fault on the board and serves the
rest; a write of the planner refuses before it commits anything and names why. The rule that a commit message
names no issue number is struck, not taken over. What another person or an agent
commits in a repository is found on the next reading, since the planner is not asked before such a commit.

| Check that leaves | Still needed | When in the life of an issue or story | On failure | Piece |
|---|---|---|---|---|
| `issues check`: a head by `issue-1.json` | yes | every reading of the board; every write of the planner | reading: fault, issue left off; write: refused | 1 moves the code, 4 makes it a fault |
| `issues check`: each wait and parent an issue | yes | every reading; a write that sets a wait or a parent | reading: fault; write: refused | 1, 4 |
| `issues check`: the index as the heads give it | no longer as a check: the planner writes the index from the heads on every write | every write rewrites it; a reading finds a hand-edited index | reading: fault, repaired by the next write | 1, 4 |
| `stories check`: a story's head by `story-2.json`, its file named after its id, no id twice | yes | every reading; every write of a story | reading: fault; write: refused | 1, 4 |
| `stories check`: a story's issues in a repository `project.yml` names and in its index | yes | every reading; adding an issue to a story | reading: fault; write: refused | 1, 4 |
| `stories check`: a story's state given in its head against its issues', a tested day only on a completed story | yes | every reading; a move of a story | reading: warning on the card; write: refused for a tested day | 3 |
| `stories check`: the story texts and `stories/README.md` as the heads give them | no longer as a check: the planner writes them on every write | every write | - | 1 |
| porelo-project's hourly Stories check | no: the board reads every repository's issues at each reading | - | - | none |
| `check-commits`: `issues/workflow.yml` in the tree | no: every repository's issues are checked against the project's `stories/workflow.yml` | every reading | fault, as a broken workflow is today | 2 |
| `check-commits`: `stats/*.jsonl` valid, closed stage records | yes | the planner writes the records itself on a move; a reading finds a broken or open record | reading: fault; write: written right | 7 |
| `check-commits`: with `stats/seed.csv`, a measured state reached only with its stage record | yes | a move or idea of the planner writes the record with it; a reading finds a state reached by another commit without one | reading: fault naming the issue | 7 |
| velocity from `stats/` | yes | the store answers it; handed to an estimate once the planner starts one | no velocity yet: an estimate is told so | 7 |
| `check-citations`: a number in `issues/` a link to its index row, the row anchored, the link reaching an anchor | yes | every reading; a write of an issue page | reading: fault naming page and line; write: refused | 5 |
| `check-citations`: an own number in `issues/` names an issue that exists | yes | every reading | fault naming page and line | 5 |
| `check-citations`: no issue number outside `issues/`, no link to an issue's file | yes | setting an issue Finished; every reading | fault naming each place; Finished is still taken | 8 |
| `check-citations`: an issue's file deleted when it is settled, its row kept | yes | setting an issue Finished | the Git store does it in the same commit | 8 |

## Acceptance

- The planner's build names no `porelo-stories`, no story, issue or stage command and no issue-link check, and its
  native executable reads and writes a project's stories and issues.
- An issue's state changed and an idea added through the planner leave the file, its head and the index as
  re-reading them expects, seen in a fresh clone; a moved issue's stage line is there and read back.
- A broken head and a broken issue link each show as a fault on the board naming the file, and the rest of the
  board is served.
- The store answers the project's velocity from the stage records.

## To be checked

- Whether the screen's form for an idea is the one the window's other lists use.
