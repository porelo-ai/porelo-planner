# PL112 — A Save Says When It Is Pushed

```json
{
  "$schema": "issue-1.json",
  "id": "PL112",
  "title": "A Save Says When It Is Pushed",
  "state": "Backlog",
  "size": "S",
  "priority": "High",
  "rank": 48,
  "blockedBy": [],
  "covers": "The contract says what a save's answer means - committed in the planner's own clones, pushed after it - and how a refresh treats a repository whose save is still pending, and the window can see a save that is not pushed yet"
}
```

**Size:** S - the contract's wording in `planner.yaml` alone; nothing the planner does changes.

**Estimate:** 33 min, the measured median of S.

**What must be true.** What the window is told about a save is what happened. Today the contract says a move or an
issue "is saved" with each commit "pushed under the person's own Git login", and that `POST /refresh` "fetches the
project's repositories at once". In fact the Git store answers once its commits stand in the planner's own clones and
pushes them in the background afterwards, the other repositories before the project's own, and a refresh does not
fetch a repository whose save is still pending. The window sees a failed push as a fault, but never a pending one, so
a person can close the window, or read the forge, before the save has reached it.

## Plan

- **Today, read in the code.** `GitStore` answers a save when its commits stand in its clones ("the pushes follow in
  the background"); `LocalSaves` keeps a pending save under `refs/planner/local` until its push lands; a refresh skips
  such a repository. `planner.yaml` describes the moves, the issue edits and the new issue as pushed, and `/refresh` as
  fetching every repository.
- **The change, as decided: the wording only.** `planner.yaml` says that a save's answer means its commits stand in
  the planner's own clones and the pushes follow in the background, the other repositories before the project's own;
  that a failed push becomes a fault on the board; and that `POST /refresh` leaves a repository with a pending save as
  it stands until the save is pushed. The moves, the issue edits, the new issue and `/refresh` are described so.
- **Tests.** None: the change is a text, and no answer changes. `ApiMatchesDescriptionTest` still holds the shapes.

## Acceptance

- The contract's description of a save and of a refresh matches what the planner does.
