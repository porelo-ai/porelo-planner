# PL17 — The Changes Stream In The Screen Package

```json
{
  "$schema": "issue-1.json",
  "id": "PL17",
  "title": "The Changes Stream In The Screen Package",
  "state": "Implementing",
  "size": "M",
  "priority": "Medium",
  "rank": 1,
  "blockedBy": ["PL06"],
  "covers": "The screen package's watchStories over the planner's event stream: the board first, every change after, 45 s of silence taken as a lost connection, reconnect with Last-Event-ID backing off 1 to 30 s"
}
```

**Why this size.** one class of the package and its tests, but its behavior is a contract the window builds on:
reading Server-Sent Events, a silence bound, reconnecting with a backoff, and which failures end the watch.

**What must be true.** A window that embeds the planning screen sees a push made elsewhere, or the planner's own
save, without asking: the package's `HttpPlanningSource.watchStories` reads `GET /projects/{project}/events`, answers
each `board` event as a snapshot, takes 45 s without a line as a lost connection, and reconnects with the last
revision as `Last-Event-ID`, backing off from 1 to 30 s, until the window stops listening.

## Plan

- **The planner's stream, as described in `openapi/planner.yaml`:** `GET /projects/{project}/events` with
  `Accept: text/event-stream` answers event `board`, `id:` the revision, `data:` one JSON object with the workflow's
  and the stories' fields, first at once and then on every change, and a comment `: keep-alive` every 15 s; a
  refusal before the stream starts answers `application/problem+json`.
- **`HttpPlanningSource.watchStories`** (`screen/lib/src/http/planner_source.dart`) opens that stream after telling
  the planner the project, as the reads do, and answers each `board` event as `PlanningSnapshot.from(data)`, never a
  revision it answered last. Lines are read with a bound: an event's data longer than `maxBody` ends the watch with
  `AnswerTooLarge`. The watch's subscription cancelled closes the connection and stops every retry.
- **Losing the connection:** 45 s without a line (`silence`), the connection closed or reset, or a `5xx` answer is a
  lost connection once the first board has arrived: the source reconnects with `Last-Event-ID` set to the last
  revision, waiting 1 s, then twice as long each time up to 30 s (`backoff`), back to 1 s after a board arrives.
  `NoSuchProject` tells the planner the project again and reconnects at once, as a read does. Before the first board,
  and for any `4xx` refusal, the failure ends the watch as an error, as the reads' refusals do today, so the board
  shows it. `silence` and `backoff` are constructor parameters so tests run in milliseconds.
- **Red first** (`screen/test/planner_source_test.dart`, against the fake planner on 127.0.0.1): the first board
  and a second one sent later arrive as two snapshots, and the same revision sent again does not; a stream that
  falls silent is reconnected with the last revision as `Last-Event-ID`; a stream the planner closes is reconnected
  after the backoff, which grows and resets after a board; `NoSuchProject` on reconnect makes one new PUT; a `4xx`
  refusal and a failure before the first board end the watch as errors; an event larger than the bound is refused;
  cancelling the watch closes the connection and makes no further call. The old test that the watch ends after one
  board is deleted. The package's test against `porelo-planner --mock` reads one board through the stream.

## Acceptance

- The red-first tests seen red, then green; `./mvnw -B -s settings.xml -Pall -pl screen verify` green.
