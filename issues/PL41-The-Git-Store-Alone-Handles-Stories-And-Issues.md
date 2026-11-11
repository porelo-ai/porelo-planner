# PL41 — The Git Store Alone Handles Stories And Issues

```json
{
  "$schema": "issue-1.json",
  "id": "PL41",
  "title": "The Git Store Alone Handles Stories And Issues",
  "state": "Accepted",
  "size": "XL",
  "priority": "High",
  "rank": 23,
  "blockedBy": [],
  "covers": "Stories and issues kept as files in Git are read, checked, indexed and written by the planner's Git store alone - the shared stories library's code taken into store/git, a broken head and a broken issue link shown as faults on the board, a new idea written through the planner, every repository's issues checked against the project's one workflow, and each issue's stages recorded so the project's velocity goes to an estimate - so the build tools can drop the library and their story, issue, stage and link commands"
}
```

**Size:** XL - it splits into two L, four M and two S of this repository, below; dropping the library, the
story and issue commands, `stage` and the issue part of `check-citations` from the build tools, and the checks from
every build, is other repositories' work.

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
- A repository's own `issues/workflow.yml` goes; every repository's issues are checked against the project's
  `stories/workflow.yml`.
- Which issue prefixes a repository owns leaves `project.yml` (`repositories[].issues`) and becomes a setting of the
  Git store adapter's configuration.

## Plan

Today `store/git` reads and writes through the published `porelo-stories` library: `GitStore`, `StoreReader`,
`ProjectFile` and `Part` use `Project`, `Story`, `StoriesIndex`, `Workflow`, `IssueHead`, `IssueIndex`,
`IssueIndexes`, `IssuesIndex` and `IssueSource`. The library is about 3600 lines of main code with three JSON
schemas and its native metadata, and about 3000 lines of tests. `StoreReader` checks a repository's issues against
its own `issues/workflow.yml`, else its own `stories/workflow.yml`. A broken head fails the whole reading, and
`Board.faults` carries only the workflow's faults. Nothing writes a new issue, and a move of an issue in a
repository with `stats/seed.csv` is refused before anything is written. In the build tools, `stage` is about 1050
lines with its tokens and as many of tests; the issue-link check of `check-citations` about 600.

1. **The library's code taken into `store/git`** (L). The classes, schemas, reachability metadata and tests move
   to a package of `store/git` that is not exported, with the planner's own names; the dependency on
   `porelo-stories` goes, and with it the rule in `AGENTS.md` and `doc/` that the index is rewritten by that
   library. Two open changes come with it: the link pattern and the central's host are taken from `project.yml`,
   not fixed to one owner, and a project's repositories are read from any clone URL - `https://`, `ssh://` and
   scp-like, on any host and under any owner. Red first: a `project.yml` whose centrals live on another host and
   owner is read with the right names and links. Unit tests over files in a temporary directory.
2. **One workflow for every repository's issues** (S). `StoreReader` reads the project's `stories/workflow.yml`
   once and checks every repository's issues against it; a repository's own `issues/workflow.yml` is no longer
   read. Red first: a repository whose own `issues/workflow.yml` names a state the project's lacks has that issue
   faulted. Unit tests.
3. **A story takes the state it is given** (S). A story whose head names a state keeps it, and the index gives a
   warning where its issues say otherwise; a story without one keeps the computed state. Red first: a story with
   a state in its head and a less advanced issue is indexed with the head's state. Unit tests.
4. **A broken head is a fault on the board** (M). A story or issue file whose head is missing, unreadable or
   against its schema is left off the board and named with its path and what is wrong in `Board.faults`, instead
   of failing the whole reading. A change to the window's contract: `Board.faults` describes faults of files too.
   Red first: a project with one broken issue head answers its board with the other stories and one fault naming
   the file. Unit tests in `store/git`, one controller test for the answer.
5. **A broken issue link is a fault on the board** (M). The store reads how issue pages name an issue - every
   number a link to its row in an index, every row anchored, every link reaching an anchor - and names each
   broken one in `Board.faults` with its file and line. Red first: an issue page linking a number whose index row
   has no anchor shows one fault naming the page and line. Unit tests over files.
6. **An idea added through the planner** (M). A new call writes an issue file with its head in state Idea under
   the repository's `issues/`, with the next free number of a prefix the repository owns, rewrites the index and
   commits and pushes as a move does. A change to the window's contract and the screen's form. Red first: an
   idea added is read back from a fresh clone with its head, its index row and its number. Unit and store tests
   over a local bare repository.
7. **An issue's stages recorded, the velocity handed to an estimate** (L). A move writes the issue's stage line
   in `stats/<ID>.jsonl` in the record's own format, also for an issue without a record yet, keeping a session's
   sources private as the record requires; the store gives the project's velocity - the median minutes by size -
   and the planner hands it to the task it starts for an estimate. A change to the window's contract where the
   start of an estimate carries it. This step takes in the open Backlog issue on recording a moved issue's stage
   ([PL22](README.md#pl22)). Red first: a move of an issue without a record writes a stage line that the record's
   reader takes back. Unit tests over files; one controller test for the estimate's start.

8. **A repository's issue prefixes from the store's configuration** (M). The Git store reads which prefixes each
   repository owns from its own configuration instead of `repositories[].issues` in `project.yml`; the index, the
   checks and a new idea's number take them from there, and a repository with issues but no prefixes configured
   is a fault on the board. Red first: a `project.yml` without `issues` and a configuration naming a prefix give
   that repository's issues and the next idea's number under the prefix. Unit tests over files.

Order: 1, 8, 2, 3, 4, 5, 6, 7 - step 8 right after the library's code is here, since the index and the checks
read the prefixes. Steps 4, 5, 6 and 7 change the window's contract and are agreed before they are built. Sum by
the medians: 2 L, 4 M, 2 S = 226 + 268 + 66 = 560 minutes.

## Acceptance

- The planner's build names no `porelo-stories`, no story, issue or stage command and no issue-link check, and its
  native executable reads and writes a project's stories and issues.
- An issue's state changed and an idea added through the planner leave the file, its head and the index as
  re-reading them expects, seen in a fresh clone; a moved issue's stage line is there and read back.
- A broken head and a broken issue link each show as a fault on the board naming the file, and the rest of the
  board is served.
- A task started for an estimate carries the project's velocity.

## Open questions

- Where does the Git store's configuration of issue prefixes live: in a file of the project's central repository,
  so every person's planner reads the same, or on each machine beside the planner's other settings? Proposed: a file
  in the central repository, since prefixes are the project's, not one person's.

## To be checked

- Which sizes the steps keep once the classes are read one by one against what the planner already holds.
- Whether the screen's form for an idea is the one the window's other lists use.
- What the window's start of a task carries today, and where the velocity fits in it.
