# PL04 — The Board Read Over REST

```json
{
  "$schema": "issue-1.json",
  "id": "PL04",
  "title": "The Board Read Over REST",
  "state": "Backlog",
  "size": "M",
  "priority": "Medium",
  "rank": 2,
  "blockedBy": ["PL03"],
  "covers": "The board's reads in the OpenAPI description and served over REST; a story's own state over its issues' derived one"
}
```

**Why this size.** the OpenAPI description of the reads, its controllers, and the source of a story's state.

**What must be true.** The window reads the board over REST, as the OpenAPI description says: the workflow, the
stories (all, or those of given states), one story with its description, a story's issues, and one issue with its
text and sub-issues; each answer carries the store's revision. A story's state is the one given it by hand when it
has one; otherwise it is the earliest workflow state among its open issues, or among all of them when all are closed,
and the answer says which of the two it is.

## Plan

- `planner/app/src/main/resources/openapi/planner.yaml`, OpenAPI 3.1, written first:
  `GET /projects/{project}/workflow`, `GET /projects/{project}/stories?state=`,
  `GET /projects/{project}/stories/{story}`, `GET /projects/{project}/stories/{story}/issues`,
  `GET /projects/{project}/issues/{issue}`, and `PUT /projects/{project}` with `{"repository": "<clone URL>",
  "branch": "<optional>"}`, the project's own repository as the window reads it from Porelo; the planner keeps it
  while it runs, takes the remote's default branch when none is named, and answers `404` for a project it was not
  given, so the window sends it and retries once; errors as `application/problem+json` with a typed `type` per refusal
  (no such story, no such issue, workflow missing, credential refused, unreachable).
- The store gives each story its state as the stories index does - a stated state wins, otherwise
  `porelo-stories`' `Workflow.storyState(stated, issueStates)` - and says whether it was stated or derived; the planner
  keeps no second copy of the rule, so it cannot drift from the index.
- `app`: controllers mapping the store's records to the description's schemas; a test reads the description and the
  controllers' mappings and fails on a path or a method one has and the other lacks.
- **Red first:** a store test that a stated state wins and is marked stated, and a story without one is marked derived
  with the state the index gives it; `ApiMatchesDescriptionTest` with one path missing.

## Acceptance

- The red-first tests seen red, then green; the native executable answers each read over a fixture store.
