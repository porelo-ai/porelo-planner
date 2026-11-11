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
  "covers": "Stories and issues kept as files in Git are read, checked, indexed and written by the planner's Git store alone, with the code of the shared stories library taken into store/git, a broken head shown as a fault on the board and a new idea written through the planner, so the build tools can drop the library and their story and issue commands"
}
```

**Size:** XL - it splits into one L, two M and one S of this repository, below; dropping the library and the
commands from the build tools and the checks from every build is other repositories' work.

**What must be true.** In a repository whose build has no story or issue tooling, the planner reads, checks,
indexes and writes stories and issues kept as files in Git by itself: an issue's state changed and an idea added
through the planner write the file, its head and the index; a broken head shows as a fault on the board.

## Plan

Today `store/git` reads and writes through the published `porelo-stories` library: `GitStore`, `StoreReader`,
`ProjectFile` and `Part` use `Project`, `Story`, `StoriesIndex`, `Workflow`, `IssueHead`, `IssueIndex`,
`IssueIndexes`, `IssuesIndex` and `IssueSource`. The library is about 3600 lines of main code with three JSON
schemas and its native metadata, and about 3000 lines of tests. A broken head fails the whole reading, and
`Board.faults` carries only the workflow's faults. Nothing writes a new issue.

1. **The library's code taken into `store/git`** (L). The classes, schemas, reachability metadata and tests move
   to a package of `store/git` that is not exported, with the planner's own names; the dependency on
   `porelo-stories` goes. Two open changes come with it: the link pattern and the central's host are taken from
   `project.yml`, not fixed to one owner, and a project's repositories are read from any clone URL - `https://`,
   `ssh://` and scp-like, on any host and under any owner. Red first: a `project.yml` whose centrals live on
   another host and owner is read with the right names and links. Unit tests over files in a temporary directory.
2. **A story takes the state it is given** (S). A story whose head names a state keeps it, and the index gives a
   warning where its issues say otherwise; a story without one keeps the computed state. Red first: a story with
   a state in its head and a less advanced issue is indexed with the head's state. Unit tests.
3. **A broken head is a fault on the board** (M). A story or issue file whose head is missing, unreadable or
   against its schema is left off the board and named with its path and what is wrong in `Board.faults`, instead
   of failing the whole reading. A change to the window's contract: `Board.faults` describes faults of files too.
   Red first: a project with one broken issue head answers its board with the other stories and one fault naming
   the file. Unit tests in `store/git`, one controller test for the answer.
4. **An idea added through the planner** (M). A new call writes an issue file with its head in state Idea under
   the repository's `issues/`, with the next free number of a prefix the repository owns, rewrites the index and
   commits and pushes as a move does. A change to the window's contract and the screen's form. Red first: an
   idea added is read back from a fresh clone with its head, its index row and its number. Unit and store tests
   over a local bare repository.
5. **The rule that the planner rewrites the index with `porelo-stories`** goes from `AGENTS.md` and `doc/` once
   step 1 stands (part of step 1).

Order: 1, 2, 3, 4. Steps 3 and 4 change the window's contract and are agreed before they are built.

## Acceptance

- The planner's build names no `porelo-stories` and no story or issue command, and its native executable reads
  and writes a project's stories and issues.
- An issue's state changed and an idea added through the planner leave the file, its head and the index as
  re-reading them expects, seen in a fresh clone.
- A broken head shows as a fault on the board naming the file, and the rest of the board is served.

## Open questions

- Until the build tools drop the library, the same code stands in two repositories. May it, for that time?
- Does a hand-over still run `check-citations`, which reads how issue pages link to issue numbers, or does it go
  with the story and issue commands?

## To be checked

- Which sizes the steps keep once the classes are read one by one against what the planner already holds.
- Whether the screen's form for an idea is the one the window's other lists use.
