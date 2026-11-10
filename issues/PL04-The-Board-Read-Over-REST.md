# PL04 — The Board Read Over REST

**Status:** Analyzed; blocked by [PL03](README.md#pl03).
**Size:** M - the OpenAPI description of the reads, its controllers, and the story-state rule with a story's own
state.

**What must be true.** The window reads the board over REST, as the OpenAPI description says: the workflow, the
stories (all, or those of given states), one story with its description, a story's issues, and one issue with its
text and sub-issues; each answer carries the store's revision. A story's state is the one given it by hand when it
has one; otherwise it is the earliest workflow state among its open issues, or among all of them when all are closed,
and the answer says which of the two it is.

## Plan

- `planner/app/src/main/resources/openapi/planner.yaml`, OpenAPI 3.1, written first:
  `GET /projects/{project}/workflow`, `GET /projects/{project}/stories?state=`,
  `GET /projects/{project}/stories/{story}`, `GET /projects/{project}/stories/{story}/issues`,
  `GET /projects/{project}/issues/{issue}`; errors as `application/problem+json` with a typed `type` per refusal
  (no such story, no such issue, workflow missing, credential refused, unreachable).
- `core`: `StoryStates.of(story, issues, workflow)` with no state name in the code, answering `(state, source:
  OWN|DERIVED)`; sub-issues do not count.
- `app`: controllers mapping the store's records to the description's schemas; a test reads the description and the
  controllers' mappings and fails on a path or a method one has and the other lacks.
- **Red first:** `StoryStatesTest` - issues in Implementing and Review read Implementing; Finished and Rejected read
  Finished; all Rejected read Rejected; a story's own state wins over its issues'; no issues reads the story's own.
  `ApiMatchesDescriptionTest` with one path missing.

## Acceptance

- The red-first tests seen red, then green; the native executable answers each read over a fixture store.
