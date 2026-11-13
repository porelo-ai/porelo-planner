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

**Size:** S - the contract's wording in `planner.yaml`; with a pending-push mark, one more member in the board's answer
and its tests. The size is for the wording alone; a mark makes it M.

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
- **The choice, for the operator** (see *Open questions*):
  - (a) **Wording only.** The contract says the answer means committed in the planner's clones, the push follows,
    a failed push becomes a fault on the board, and a refresh leaves a repository with a pending save as it stands.
  - (b) **Wording and a pending-push mark.** As (a), and the board's answer names the repositories whose save is not
    pushed yet, so the window can show it and warn before it closes.
- **Tests.** For (a): none; the change is a text. For (b): the board names a repository while its push is pending and
  stops naming it once the push lands, red first. Harm: a person leaves while a save has not reached the forge.

## Acceptance

- The contract's description of a save and of a refresh matches what the planner does; with (b), the window can see a
  pending push.

## Open questions

- (a) the wording alone, or (b) the wording and a pending-push mark on the board?
