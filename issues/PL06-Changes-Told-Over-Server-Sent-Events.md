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

- **The contract, as agreed with the window:** `GET /projects/{project}/events` (`text/event-stream`) sends event
  `board`, its `id:` the revision and its `data:` one JSON object `{revision, states, phases, work, faults, stories}` -
  the workflow's answer and the stories' answer in one - first at once, then on every change. A comment line
  `: keep-alive` every 15 s; the window takes 45 s of silence as a lost connection and reconnects with
  `Last-Event-ID`, backing off 1 to 30 s; a reconnect always gets the current board first. `POST
  /projects/{project}/refresh` fetches now and answers `200 {revision}`. No bound on streams per token; a slow
  reader gets only the newest board. Both described in `openapi/planner.yaml`, schemas `Board` and `Refreshed`.
- **`core`: `Changes`**, plain Java over a `Supplier<Board>` that refreshes (the app hands it
  `projects.store(name).refresh()`, so a project given anew is read from its new store). `subscribe()` answers a
  `Subscription` holding the current board; `Subscription.next(Duration)` waits for a newer board and keeps only the
  newest (one slot, replaced, never a queue), answering empty after the wait, so the caller sends a keep-alive.
  `poll()` refreshes and hands a board with a new revision to every subscription; `saved(Board)` does the same for the
  planner's own saves, which the moves will call. A closed subscription is dropped; a failed refresh is logged with
  its scrubbed words and polling goes on.
- **`app`: `ChangesRegistry`** keeps one `Changes` per project while it has a subscriber and schedules its `poll()`
  every `planner.poll-interval` (default 3 min) on one scheduler, cancelled with the last subscriber. **`EventsController`**
  answers an unknown project before the stream starts (`NoSuchProject`, problem+json), then sends from a virtual
  thread through `SseEmitter` without a timeout; a failed send closes the subscription. **`BoardAnswers`**: the
  mapping from a `Board` to the workflow's and the stories' answers, moved out of `BoardController` and used by both.
- **Red first:** `ChangesTest` over a fake store - a revision changed outside reaches every subscriber, the same
  revision does not; a save reaches each once; a slow subscriber gets only the newest; a closed one is dropped and the
  last one gone stops the polling; a refresh that fails reaches no one and the next one does. `EventsStreamTest` over
  the fixture's HTTPS remote - the first event, a second one after a commit and `POST .../refresh`, the keep-alive,
  the stream closed by the client ends its subscription. `NativeStartIT` - a change made in the test server's remote
  streamed by the native executable.

## Acceptance

- The red-first tests seen red, then green; the native executable streams a change made in the test server's remote.
