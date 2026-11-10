# PL16 — The Planner's HTTP Client In The Screen Package

```json
{
  "$schema": "issue-1.json",
  "id": "PL16",
  "title": "The Planner's HTTP Client In The Screen Package",
  "state": "Review",
  "size": "M",
  "priority": "High",
  "rank": 1,
  "blockedBy": ["PL04"],
  "covers": "A PlanningSource over HTTP in the screen package: planner.json read, the project PUT, the board reads, problem+json as PlanningRefusal; the window wraps it and hands it in"
}
```

**Why this size.** one Dart class over the board reads with its tests against a fake HTTP server, and a small
PlanningClient around it; no contract change, since it calls the API as `openapi/planner.yaml` describes it.

**What must be true.** The window gets a `PlanningSource` from the screen package that talks to the planner over
HTTP: it reads the planner's address and token from `planner.json`, gives the planner the project before its first
read and again after a `NoSuchProject`, serves the workflow, the stories, a story, its issues and an issue from the
planner's answers, and turns each `application/problem+json` refusal into a `PlanningRefusal` with the name after
`urn:porelo-planner:`, its `parameters` and its `detail` as the description. The token is kept in memory alone, never
in a log or a URL. Calls the planner does not offer yet (moves, work, reading rounds, the event stream) answer
`PlanningUnsupported`, so the screen leaves those features out.

## Plan

- `screen/lib/src/http/planner_source.dart`: `HttpPlanningSource implements PlanningSource`, built from the
  address file and the project's own repository as Porelo names it; the snapshot of `stories(project)` and
  `planningWorkflow(project)` from `GET .../workflow` and `GET .../stories`, the others from their reads.
- No `PlanningClient`: one needs a connection state and task events the planner has no call for, so the window keeps
  its own client around the source; no class named `EmbeddedPlanning`, which is the window's.
- The refusals the source makes itself, besides the planner's: `PlannerUnreachable` (the planner does not run, unlike
  the planner's `Unreachable`, a Git remote), `TimedOut`, `AnswerTooLarge`, `BadAnswer`, `RevisionMoved`,
  `NoAddress`, `BadAddress` and `InvalidName`, the last before any call.
- **Red first:** `planner_source_test.dart` against a local `HttpServer`: the PUT before the first read and once
  more after a 404 `NoSuchProject`; each read mapped to the records the screen already reads; each refusal named;
  the token in the `Authorization` header and nowhere else.

- **Code reading:** read by another model; every finding is fixed with a test seen red, but one. Declined: fields the
  screen's models leave out (an issue's text and sub-issues, a parent, a story's `stateGiven`, a rule's models) - the
  source hands over every field the planner answers, and what the screen shows is the screen's choice.

## Acceptance

- The red-first tests seen red, then green; the source reads the board of `porelo-planner --mock`.
