# PL17 — The Changes Stream In The Screen Package

```json
{
  "$schema": "issue-1.json",
  "id": "PL17",
  "title": "The Changes Stream In The Screen Package",
  "state": "Review",
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
  and the stories' fields, first at once (on a reconnect too) and then on every change, and a comment `: keep-alive`
  every 15 s; a refusal before the stream starts answers `application/problem+json`.
- **`HttpPlanningSource.watchStories`** (`screen/lib/src/http/planner_source.dart`) opens that stream after telling
  the planner the project, checked against `projects(project)` before each connection as the reads do: another
  repository or branch is told anew and the last revision forgotten. The request sets `followRedirects = false`, the
  token in the `Authorization` header alone; the answer's headers, a refusal's body and the first board each come
  within `timeout`. Each `board` event is answered as `PlanningSnapshot.from(data)`, never the revision answered
  last; data that is no complete board is `BadAnswer`.
- **Bounds, before decoding:** the bytes of the unfinished line and the undispatched event together stay within
  `maxBody`, else `AnswerTooLarge`, comment lines not counted; an `id:` longer than 200 characters is `BadAnswer`, so `Last-Event-ID` stays a
  short header.
- **Losing the connection** after the first board: 45 s without a whole line (`silence`), the connection closed or reset,
  the planner unreachable or not answering in time, or `503`/`504`. The source reconnects with `Last-Event-ID`,
  waiting 1 s, then twice as long each time up to 30 s (`backoff`, `backoffMost`), back to 1 s once a connection
  brought a new revision or stayed open for `backoffMost`. `NoSuchProject` tells the planner the project again and
  reconnects at once, once per attempt; a second one ends the watch. A connection not restored within `silence` ends
  the watch with the last failure, the time counted from the end of the last connection that brought a board, and the
  last wait cut to what is left of it, so the board says its planner is gone; the board loads again when
  its provider changes, as it does for any failure.
- **What ends the watch at once,** as an error the board shows: any failure before the first board, the end of a
  stream before its first board, `4xx` refusals, `502` (`WorkflowMissing`, `CredentialRefused`, `Unreadable`),
  `BadAnswer` and `AnswerTooLarge` (a `503` too large to read too).
- **Cancel** aborts a request in flight, cancels the answer's subscription, a refusal's body read and the silence,
  first-board and backoff timers (all `Timer`s, never `Future.delayed`), and makes no further call; a step that was
  waiting when the watch was cancelled does not go on.
- **The board** (`screen/lib/src/planning_board.dart`, `_listen`) takes a reply that is a complete board as it is,
  with its workflow or only its faults, instead of keeping the prior workflow or reading the workflow again.
- `silence`, `backoff` and `backoffMost` are constructor parameters, so tests run in milliseconds.
- **Red first** (`screen/test/planner_source_test.dart`, against the fake planner on 127.0.0.1): two boards and no
  repeat; a silent stream reopened with the last revision as `Last-Event-ID`; the backoff doubling to its bound and
  starting over; `NoSuchProject` naming the project again once; a refusal and a failure before the first board ending
  the watch; a lost connection not restored within `silence` ending it; an event past the bound refused; a cancel
  closing the connection with no further call; a redirect not followed. `planning_board_test.dart`: a streamed board
  whose workflow changed shows the new workflow without another read. The old test that the watch ends after one
  board is deleted.
- **The code reading's findings, each with a test seen red first:** silence counted per whole line, not per chunk; an
  `id:` past 200 characters refused; a stream that ends before its board ends the watch, after the first board too; a
  connection that brought a board starts the loss clock over; the last wait cut to the silence left; the waits
  starting over after a new revision (or a connection open for `backoffMost`); a streamed board with only faults
  shown. Kept without a test of its own: cancel ending the connection's timers and body read at once, since the
  harm, a call after the cancel, is the cancel test's and the rest only frees them sooner. Declined: a `503` with an
  unreadable body is retried like any `503`, since the loss clock ends it within `silence`; the order of a streamed
  board against a slower read is an issue of its own, [PL21](README.md#pl21).
- **Hand acceptance**, against the native executable in `--mock` with a runtime directory of its own and a watch
  with a 3 s silence bound: the first board (`mock@1`, 34 stories, 15 states) came in 72 ms; after the planner was
  stopped, the watch ended 3.4 s later with `PlannerUnreachable`. A planner started anew writes a new port and token,
  so a reconnect to it is the window's: it reads the address again and gives the board a new source.

## Acceptance

- The red-first tests seen red, then green; `./mvnw -B -s settings.xml -Pall -pl screen verify` green; a watch run
  by hand against `porelo-planner --mock`, which answers one board and ends with an error once the planner stops.
