# PL10 — A Mock Mode For The Window's Tests

```json
{
  "$schema": "issue-1.json",
  "id": "PL10",
  "title": "A Mock Mode For The Window's Tests",
  "state": "Implementing",
  "size": "M",
  "priority": "Medium",
  "rank": 3,
  "blockedBy": ["PL04"],
  "covers": "`--mock` serves the window's mock board through the same API and SSE, moves in memory, a reset between tests"
}
```

**Why this size.** the window's mock board as data, a store that serves it and takes moves in memory, and the start
switch.

**What must be true.** `porelo-planner --mock` serves the window's mock board through the same REST API and SSE,
with no Git and no network: the stories, issues, work and reading states the window's planning tests expect, moves
taken in memory and told over SSE, and a reset between tests.

## Plan

- The board of the window's Dart mock (`planning_data.dart`, `planning_moves.dart`) turned once into
  `planner/app/src/main/resources/mock/board.json`, read with Jackson; `MockStore implements PlanningStore`, in
  memory.
- `--mock`, and nothing else, registers a `MockMode` bean at start; no profile, property or environment variable
  turns the mode on. In it every project is served from `MockStore` after the same source checks as a real start,
  `POST /mock/reset` restores the board and forgets every project given, and the start is refused unless the runtime
  directory is given and is not the account's own, so the person's window never finds a mock.
- **Built:** the reads, the reset and the guard, in the JVM and the native executable. **Still to build:** moves in
  memory (with the moves' own API) and the event stream (with the changes' own), each once the real planner offers it.
- **Red first:** `MockModeTest` - the board answers the mock's stories; a move is seen in the next read and over SSE;
  reset restores it; without `--mock` `/mock/reset` is not found.

- **Code reading of the reads:** read by another model; every finding is fixed with a test seen red, but one.
  Declined: a malformed `mock/board.json` read past the JSON check - the board is a resource of the build, read on
  every build by the mock test and the native test, so a broken one fails the build and never reaches a person.

## Acceptance

- The red-first test seen red, then green; the native executable started with `--mock` answers the board.

## To be checked

- Which of the window's planning tests read which mock values, so the JSON holds everything they assert, read in
  porelo-frontend's tests.
