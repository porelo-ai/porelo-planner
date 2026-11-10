# PL04 — The Board Read Over REST

```json
{
  "$schema": "issue-1.json",
  "id": "PL04",
  "title": "The Board Read Over REST",
  "state": "Implementing",
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

- **The description first:** `planner/app/src/main/resources/openapi/planner.yaml`, OpenAPI 3.1. Its schemas take the
  shapes the planning screen's records already read (`screen/lib/src/models.dart`), so the window maps nothing; every
  answer carries the board's `revision`.
  - `PUT /projects/{project}` with `{"repository": "<clone URL>", "branch": "<optional>"}`: the project's own
    repository as the window reads it from Porelo. The planner reads `project.yml` there for the other repositories,
    keeps the project while it runs and answers `204`; the same body again changes nothing, another one replaces the
    project. Any other call for a project it was not given answers `404` (`no-such-project`), so the window sends it
    and retries once.
  - `GET /projects/{project}/workflow` -> `{revision, states: [{name, kind: WAITING|WORKING|CLOSED}], phases: [{name,
    states}], work: [{state, from, done, then, blocked, per, modes, order, models}], faults: [text]}`; a state is
    `WORKING` when it is a key of the sound work rules, `CLOSED` when the workflow says closed, else `WAITING`.
  - `GET /projects/{project}/stories?state=<name>&state=...` -> `{revision, stories: [story]}`, a story `{id, title,
    state, stateGiven, priority, link, issues: [issue]}` without its description; `stateGiven` is true when its
    head states the state, false when its issues give it.
  - `GET /projects/{project}/stories/{story}` -> `{revision, story}` with its `description`.
  - `GET /projects/{project}/stories/{story}/issues` -> `{revision, issues: [issue]}`, an issue `{id, repository,
    title, state, size, blockedBy, parent, link}`.
  - `GET /projects/{project}/issues/{issue}` -> `{revision, issue}` with its `text` and `subIssues` (ids).
  - Refusals as `application/problem+json` with a `type` per refusal: `no-such-project`, `no-such-story`,
    `no-such-issue`, `workflow-missing`, `credential-refused` (naming the repository), `unreachable`, `unreadable`,
    `not-a-clone-url`; never a credential or a local path in the text.
- **`store/api`:** `Workflow` carries which states are closed; `Story` carries whether its state was stated. The store
  already gives the state as the stories index does (a stated one wins, else `Workflow.storyState`), so the planner
  keeps no second copy of the rule.
- **`app`:** a `Projects` registry (project name -> its `GitStore`, made from `ProjectFile.repositories` over the
  cache directory, one per project, replaced whole on a new `PUT`), controllers mapping the store's records to the
  schemas, the state kinds from `WorkRules.check`, and one exception handler mapping each `StoreException` to its
  problem type. Names and ids are checked by `Ids` before they reach the store.
- **Red first:** `ApiMatchesDescriptionTest` reads the description and the controllers' mappings and fails on a path
  or method one has and the other lacks (seen red with one path left out); `BoardReadsTest` over a fake store: each
  read's fields and revision, `stateGiven` both ways, the kinds, `404` before a `PUT`, each `StoreException` as its
  problem type with no path or credential in it.

## Acceptance

- The red-first tests seen red, then green; the native executable answers each read over a fixture store.
