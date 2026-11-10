# PL06 — Changes Told Over Server-Sent Events

```json
{
  "$schema": "issue-1.json",
  "id": "PL06",
  "title": "Changes Told Over Server-Sent Events",
  "state": "Implementing",
  "size": "M",
  "priority": "Medium",
  "rank": 5,
  "blockedBy": ["PL04"],
  "covers": "Changes told over SSE: polling, read now, the planner's own saves, a dropped connection"
}
```

**Why this size.** a fetch loop per project with a read-now call, an SSE stream per window and its tests, including a
broken connection.

**What must be true.** A window subscribed to a project's changes gets the whole board first and then a new board
whenever it changes: by the planner's own save, or by a push someone made elsewhere, seen within the polling period or
at once after the window asks to read now. A dropped connection is noticed by both sides; a window that reconnects
with the last revision it saw gets the current board.

## Plan

- `GET /projects/{project}/events` (`text/event-stream`): event `board` with the board and its revision, then on
  every change; `Last-Event-ID` is the revision; a comment line as keep-alive every 15 s.
- `POST /projects/{project}/refresh` fetches now and answers the revision.
- `core`: `Changes`, one per project while it has a subscriber: polls the store's `refresh()` every three minutes
  (configurable), compares revisions, and signals on a change or after a save; a slow subscriber gets only the newest
  board.
- **Red first:** `ChangesTest` with a fake store - a revision change from outside reaches the subscriber; a save
  reaches it once; a subscriber gone is dropped. `EventsStreamTest` - a second event after the fixture's remote
  moved; the stream closed by the client ends its loop.

## Acceptance

- The red-first tests seen red, then green; the native executable streams a change made in the test server's remote.
