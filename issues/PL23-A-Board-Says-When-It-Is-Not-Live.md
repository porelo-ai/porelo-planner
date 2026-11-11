# PL23 — A Board Says When It Is Not Live

```json
{
  "$schema": "issue-1.json",
  "id": "PL23",
  "title": "A Board Says When It Is Not Live",
  "state": "Review",
  "size": "S",
  "priority": "Medium",
  "rank": 13,
  "blockedBy": [],
  "covers": "The planning board stands as live only while its changes stream is open, says that it is catching up while it reconnects and that Reload shows the current state once it is not; an issue's screen shows its sub-issues"
}
```

**Size:** S - the screen package and its tests alone; no contract another repository reads changes.

**What must be true.** A person reading the planning board can tell whether it follows the project's changes: it never
stands as live once its source has ended the stream, it says that it is catching up while the stream reconnects, and
once it is not live it says that *Reload planning* shows the current state, in words that name the planner rather than
a machine. An issue's screen shows the issue's sub-issues, as the planner serves them with the issue read alone.

## Plan

- **Not live after the stream ends:** `PlanningBoard`'s watch (`screen/lib/src/planning_board.dart`, `onDone`) keeps
  `live` set when a source closes its stream. It sets `live` to false, so moves and story actions wait for *Reload
  planning*.
- **Catching up:** `HttpPlanningSource`'s watch (`screen/lib/src/http/planner_source.dart`, `_run`) reconnects inside
  its stream, and the board cannot see it. A small optional interface in `client.dart`, implemented by the HTTP
  source, tells for a project whether its watch is reconnecting, and notifies its listeners. The board shows *catching
  up* while it is, and drops it with the next board. `PlanningSource` itself does not change, so a window's own source
  needs nothing.
- **Words:** in `screen/lib/src/ui/planning_view.dart`, a board that is not live says that *Reload planning* shows the
  current state, beside the refusal's words when there are any. The waiting texts name the planner, not a machine.
- **Sub-issues:** `PlanIssueDetail` (`screen/lib/src/models.dart`) reads `subIssues`, the IssueDetail's required list
  of issue ids. `planning_issue_view.dart` lists them, and each one opens like an issue of the story.
- **Red first:** unit tests over a fake source: a stream that answers one board and closes leaves the board not live; a
  reconnect sets and clears *catching up*; an issue with `subIssues` lists and opens them. A loopback HTTP case shows
  the source reporting the reconnect.

## Acceptance

- A source that answers one board and closes its stream leaves the board not live; a test over a fake source that
  does so fails today, since `PlanningBoard`'s watch keeps `live` set when the stream is done.
- While the watch reconnects after a lost connection, the board says that it is catching up, and says it no longer
  once a board arrives.
- A board that is not live says that Reload shows the current state, beside the refusal's own words when there are
  any; the waiting text names the planner.
- An issue read alone with sub-issues shows each of them on its screen, and opens it; a test over a fake source
  serving `subIssues` fails today, since `PlanIssueDetail` does not read them.

## Taken from the code reading

- **An opened sub-issue's id, an issue's "What must be true" and the workflow's faults are shown without control or
  bidirectional characters.** Navigation and reads keep the planner's id unchanged.
- **Moved to [PL25](README.md#pl25), since each predates this change or needs a case the screen does not make:**
  - a board held back during a reload or a save stands as live before it is accepted;
  - two watches of one source on the same project can hold each other in catching up;
  - a board disposed inside its own notification may still open a watch.
