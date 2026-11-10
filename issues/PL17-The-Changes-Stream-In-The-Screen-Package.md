# PL17 — The Changes Stream In The Screen Package

```json
{
  "$schema": "issue-1.json",
  "id": "PL17",
  "title": "The Changes Stream In The Screen Package",
  "state": "Backlog",
  "size": "-",
  "priority": "Medium",
  "rank": 1,
  "blockedBy": ["PL06"],
  "covers": "The screen package's watchStories over the planner's event stream: the board first, every change after, 45 s of silence taken as a lost connection, reconnect with Last-Event-ID backing off 1 to 30 s"
}
```

**What must be true.** A window that embeds the planning screen sees a push made elsewhere, or the planner's own
save, without asking: the package's `HttpPlanningSource.watchStories` reads `GET /projects/{project}/events`, answers
each `board` event as a snapshot, takes 45 s without a line as a lost connection, and reconnects with the last
revision as `Last-Event-ID`, backing off from 1 to 30 s, until the window stops listening.
