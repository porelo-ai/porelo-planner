# PL23 — A Board Says When It Is Not Live

```json
{
  "$schema": "issue-1.json",
  "id": "PL23",
  "title": "A Board Says When It Is Not Live",
  "state": "Backlog",
  "size": "-",
  "priority": "Medium",
  "rank": 13,
  "blockedBy": [],
  "covers": "The planning board stands as live only while its changes stream is open, says that it is catching up while it reconnects and that Reload shows the current state once it is not; an issue's screen shows its sub-issues"
}
```

**What must be true.** A person reading the planning board can tell whether it follows the project's changes: it never
stands as live once its source has ended the stream, it says that it is catching up while the stream reconnects, and
once it is not live it says that *Reload planning* shows the current state, in words that name the planner rather than
a machine. An issue's screen shows the issue's sub-issues, as the planner serves them with the issue read alone.

## Acceptance

- A source that answers one board and closes its stream leaves the board not live; a test over a fake source that
  does so fails today, since `PlanningBoard`'s watch keeps `live` set when the stream is done.
- While the watch reconnects after a lost connection, the board says that it is catching up, and says it no longer
  once a board arrives.
- A board that is not live says that Reload shows the current state, beside the refusal's own words when there are
  any; the waiting text names the planner.
- An issue read alone with sub-issues shows each of them on its screen, and opens it; a test over a fake source
  serving `subIssues` fails today, since `PlanIssueDetail` does not read them.
