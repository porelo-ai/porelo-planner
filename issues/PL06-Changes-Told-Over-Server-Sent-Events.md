# PL06 — Changes Told Over Server-Sent Events

```json
{
  "$schema": "issue-1.json",
  "id": "PL06",
  "title": "Changes Told Over Server-Sent Events",
  "state": "Review",
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
- **`core`: `Changes`**, plain Java over one project's store. `subscribe()` answers a `Subscription` holding the
  store's current board (`board()`, never a fetch); `Subscription.next(Duration)` waits for a newer board and keeps only the
  newest (one slot, replaced, never a queue), answering empty after the wait, so the caller sends a keep-alive.
  `poll()` refreshes and hands a board with a new revision to every subscription; `saved(Board)` does the same for the
  planner's own saves, which the moves will call; both one at a time, so an older board never follows a newer one.
  A closed subscription is dropped; `closeAll()` ends every one and wakes its wait.
- **`app`: `ChangesRegistry`** keeps one `Changes` per project while it has a subscriber and schedules its `poll()`
  every `planner.poll-interval` (default 3 min) on one scheduler, cancelled with the last subscriber; a failed poll
  is logged with the store's scrubbed words and the next one tries again. A project given anew or forgotten
  (`Projects.onRetired`) ends its streams, so the windows reconnect to its new store or learn it is gone; the first
  board is read before the project is followed, so one that cannot be read leaves no polling; `refresh` with no
  stream fetches without following. **`EventsController`** checks the name and reads the first board before the
  stream starts, so `InvalidName`, `NoSuchProject` and the store's refusals answer as problem+json, then sends from a
  virtual thread through `SseEmitter` without a timeout; the emitter's end or error closes the subscription at once.
  `BoardController.boardOf` maps a `Board` for both; `Answers.Board` is registered for the native image.
- **Red first:** `ChangesTest` over a fake store - a revision changed outside reaches every subscriber, the same
  revision does not; a save reaches each once; a slow subscriber gets only the newest; a closed one is dropped and the
  last one gone stops the polling; a refresh that fails reaches no one and the next one does. `EventsStreamTest` over
  the fixture's HTTPS remote - the first event, a second one after a commit and `POST .../refresh`, the keep-alive,
  the stream closed by the client ends its subscription. `NativeStartIT` - a change made in the test server's remote
  streamed by the native executable.

- **Plan reading:** read by another model; its findings are in this plan. Declined: a bound on streams per project or
  token (the window's contract has none, and only the person holds the token); coalescing refresh calls in a loop
  (the person's own calls, serialized by the store's lock); one timer for the board reads' 30 s check and the
  stream's poll (the check runs only when a read asks for it, and the stream catches up at its next poll).

## Acceptance

- The red-first tests seen red, then green; the native executable streams a change made in the test server's remote.

- **Code reading:** read by another model. Fixed, each with a test seen red first: a save told after a newer poll
  told the older board (`saved()` now reads the store's board under the fetching lock); a subscriber whose first
  board was newer than the others' was told it again (each subscription keeps the revision it holds and is never
  told it twice); a slow first read of one project held every other project's stream (the first read runs outside
  the registry's lock); the planner's stop waited for open streams (they end before the web server stops). Tests
  added for the polling itself and its end. Declined: a refusal on the stream asked with `Accept: text/event-stream`
  answers `application/problem+json` already, measured, and the test now asks that way; a refresh answering while
  a stream opens tells that stream at the next poll, since the window opens its stream before it asks to read now;
  one scheduler thread for every project's polls, since polls are minutes apart and each Git call is bounded; the
  mock reset racing a project given anew, since the reset runs only between the window's tests.
- **A reader that stops reading without closing its connection** keeps its stream's thread and its project's polling
  until its socket closes, which the window's process does when it ends; only the person holds the token, so it
  costs the person's own planner and nothing else.
