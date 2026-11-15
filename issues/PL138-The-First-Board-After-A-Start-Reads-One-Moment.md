# PL138 — The First Board After A Start Reads One Moment

```json
{
  "$schema": "issue-1.json",
  "id": "PL138",
  "title": "The First Board After A Start Reads One Moment",
  "state": "Backlog",
  "size": "S",
  "priority": "Medium",
  "rank": 61,
  "blockedBy": [],
  "covers": "The first board a started planner answers from its clones reads every repository at the commits of one earlier check, the own repository too, so a story that names an issue added since the last run is not shown as naming no issue of the project until the first check ends"
}
```

**Size:** S - two classes of the Git store and their unit tests; no contract.

**What must be true.** A person who opens Planning right after the planner started sees either the board as the last
run left it or the board as the repositories hold it now, never a mix that names a just-added issue as no issue of
the project's repositories.

## Plan

- Today `GitProjects.opened` calls `ProjectFile.repositories`, which fetches the own repository into
  `refs/planner/fetched` to read `project.yml`. Then `GitStore.board()` answers `fromClones()`, the board built from each
  clone's `refs/planner/fetched`: the own repository at the commit just fetched, every other at the last run's. A story
  of the newer own repository that names an issue added since then in another repository is left out with "is no
  issue of the project's repositories" until the background check that `fromClones` starts at once ends, some 25
  seconds on a project of fifteen repositories. Seen in the planner's log after two starts on one day, with 28 and 11
  such faults on the first board and none on the board read about 25 seconds later.
- `ProjectFile.repositories` reads `project.yml` from a fetch into a ref of its own, `refs/planner/opened`, and leaves
  `refs/planner/fetched` as the last check wrote it, so `fromClones` reads one moment for every repository; the check
  `fromClones` starts at once then moves each repository forward together. `Remote.fetch` takes the local ref it writes;
  `GitStore.refreshed` and `LocalSaves` keep `refs/planner/fetched`.
- A project whose `project.yml` names a repository the clones do not hold yet still gets no board from the clones, as
  today: `fromClones` finds no fetched commit and the first board is read in full.
- Red first, in `GitProjectsTest` over local bare repositories: a project is opened and read; an issue is added to one
  repository and a story naming it to the own repository; the project is opened anew, and its first board leaves out
  nothing and names the earlier revision. Harm: a fault shown for an issue that exists, and a board whose revision
  pairs commits no check ever read together.

## Acceptance

- The unit test above, seen red against today's open; the Git store's suite passes.
