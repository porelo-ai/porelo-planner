# PL63 — A Fresh Board At Once And Its Faults Folded

```json
{
  "$schema": "issue-1.json",
  "id": "PL63",
  "title": "A Fresh Board At Once And Its Faults Folded",
  "state": "Review",
  "size": "S",
  "priority": "Highest",
  "rank": 1,
  "blockedBy": [],
  "covers": "A window that follows a project gets the board fetched from the remotes within seconds, not only the clones' older one until the first poll; Reload planning fetches; the board's faults take one line, folded until the person opens them"
}
```

**Size:** S - one change in the app's polling, one in the screen's reload and its fault list, each with its test.

**What must be true.** A person who opens the planning board sees what the repositories hold now, and a list of
faults never pushes the board out of sight.

## Plan

- `ChangesRegistry` starts a followed project's polling with a fetch at once, then every interval; today the first
  fetch waits a whole interval, so a planner just started shows the clones as an earlier run left them, with faults
  long fixed.
- The screen's Reload planning asks the planner to fetch (`POST /projects/{project}/refresh`) before it reads;
  today it reads the board the planner holds and fetches nothing.
- The board's faults show as one line, "N problems in the stories and issues", folded by default, the full list
  shown on demand.
- Red first: in `ChangesRegistryTest`, a push before a window follows is told within seconds under an interval of an
  hour; in the screen, Reload planning calls the planner's refresh.

## Acceptance

- After the planner starts, the window shows the repositories' current board within seconds; eight faults take one
  line until opened.
