# PL10 — A Mock Mode For The Window's Tests

**Status:** Analyzed; blocked by [PL04](README.md#pl04).
**Size:** M - the window's mock board as data, a store that serves it and takes moves in memory, and the start
switch.

**What must be true.** `porelo-planner --mock` serves the window's mock board through the same REST API and SSE,
with no Git and no network: the stories, issues, work and reading states the window's planning tests expect, moves
taken in memory and told over SSE, and a reset between tests.

## Plan

- The board of the window's Dart mock (`planning_data.dart`, `planning_moves.dart`) turned once into
  `planner/app/src/main/resources/mock/board.json`, read with Jackson; `MockStore implements PlanningStore`, in
  memory.
- `--mock` activates the Spring profile `mock`; `POST /mock/reset` exists only in it.
- **Red first:** `MockModeTest` - the board answers the mock's stories; a move is seen in the next read and over SSE;
  reset restores it; without `--mock` `/mock/reset` is not found.

## Acceptance

- The red-first test seen red, then green; the native executable started with `--mock` answers the board.

## To be checked

- Which of the window's planning tests read which mock values, so the JSON holds everything they assert, read in
  porelo-frontend's tests.
