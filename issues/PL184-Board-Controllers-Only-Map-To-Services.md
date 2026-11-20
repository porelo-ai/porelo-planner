# PL184 — Board Controllers Only Map to Services

```json
{
  "$schema": "issue-1.json",
  "id": "PL184",
  "title": "Board Controllers Only Map to Services",
  "state": "Idea",
  "size": "L",
  "priority": "High",
  "rank": 1,
  "blockedBy": ["PL182"],
  "covers": "The planner's REST controllers only check a request, map it to a record, call one service method and map its answer and refusals back; the board's and the runs' use cases live in services tested without HTTP"
}
```

**Why this size.** L: `BoardController` (1135 lines, 20 endpoints) runs most of its writes' use cases itself; they
move into services with their own tests, and the controller tests shrink to the mapping; no contract changes.

**What must be true.** Each use case of the planner - a move, a write, a run's start, report, result and stop - is
one service method that knows no transport, so it is tested without HTTP and an MCP method or a command can call it
the same way.

## Plan

- **Read in the code**: `BoardController` takes the project's lock (`projects.work(name).alone(...)`), reads the
  board, checks the revision, applies `core`'s rule, writes through the store (`store.save`, `createIssue`,
  `saveResult`) and announces the change (`changes.saved(name)`) inside its handlers for moves, a new issue, the
  worker, the priority, an idea's hand-in, the work's start, a task's event and result, a stop and a reading's
  choice; it also builds the board answers from `WorkRun` snapshots. `EventsController`, `HealthController`,
  `ProofController` and `LogLevelController` only map. The screen's widgets call `PlanningBoard` and `StoryWork`,
  which hold the window's side of each use case, so the screen needs no change.
- **Services**: `BoardService` (moves and their preview, new issue, worker, priority, and [PL182](README.md#pl182)'s
  writes, which already go through it) and `WorkService` (proposal, start, events, result, stop, reading, ideas),
  both in `planner/app` without HTTP types, each method taking a record and answering a record or a refusal
  (`StoreException`, `core`'s refusals); `Problems` maps refusals to HTTP as today.
- **Moved one endpoint at a time**, each step with the service's tests over a fake store (lock, stale revision,
  rule, write, event) taken from the controller tests, which keep only what the mapping does.
- Not moved: `WorkCommands`' `issues`, `stories` and `stage` and `CarryOverCommand`'s assembly of the plan from the
  checkouts, which go with the Markdown store ([PL178](README.md#pl178)).
- Red first: none beyond the moved tests, which must fail when the service skips its lock, its revision check or its
  event; each is seen red once against the service.

## Acceptance

- No method of a controller calls a store, a lock or the change registry; the full suite and the native start pass
  unchanged.
