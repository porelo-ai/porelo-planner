# PL04 — The Board Read Over REST

```json
{
  "$schema": "issue-1.json",
  "id": "PL04",
  "title": "The Board Read Over REST",
  "state": "Review",
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
  answer carries the board's `revision`, and fields the screen does not read yet are added beside them.
  - `PUT /projects/{project}` with `{"repository": "<clone URL>", "branch": "<optional>"}`: the project's own
    repository as the window reads it from Porelo. The planner reads `project.yml` there for the other repositories,
    keeps the project while it runs and answers `204`; the same body again changes nothing. Another body drops the
    project first and clears its cache directory under the project's lock, so no clone of the old body is read for
    the new one and a failed `PUT` leaves the project unknown, never half old. `PUT`s of one project run one at a
    time. Any other call for a project it was not given answers `404` (`NoSuchProject`), so the window sends it and
    retries once. At most 8 projects are kept; one more answers `409` (`TooManyProjects`).
  - `GET /projects/{project}/workflow` -> `{revision, states: [{name, kind: WAITING|WORKING|CLOSED}], phases: [{name,
    states}], work: [{state, from, done, then, blocked, per, modes, order, models}], faults: [text]}`; a state is
    `CLOSED` when the workflow says closed, `WORKING` when it is a key of the sound work rules, else `WAITING`.
    `WorkRules.check` refuses a rule on a closed state, so no state is both.
  - `GET /projects/{project}/stories?state=<name>&state=...` -> `{revision, stories: [story]}`, a story `{id, title,
    state, stateGiven, priority, link, issues: [issue], work: null}` without its description; `stateGiven` is true
    when its head states the state, false when its issues give it; `work` is null until work is started from the
    planner, so the screen does not ask for it per story.
  - `GET /projects/{project}/stories/{story}` -> `{revision, story}`, the same story with its `description`.
  - `GET /projects/{project}/stories/{story}/issues` -> `{revision, issues: [issue]}`, an issue `{id, repository,
    title, state, size, blockedBy, parent, link}`.
  - `GET /projects/{project}/issues/{issue}` -> `{revision, issue}`, the issue with `whatMustBeTrue` (the paragraph
    of that name, absent when the text has none), `text` and `subIssues` (ids); findings and sub-agents stay
    absent, read by the screen as not reported, until reading rounds exist.
  - Refusals as `application/problem+json`: `type` is `urn:porelo-planner:<Name>`, the name the screen's
    `PlanningRefusal` carries, `detail` its words, and `parameters` what it names (`project`, `story`, `issue`,
    `repository`). Names: `NoSuchProject` (404), `NoSuchStory` (404), `NoSuchIssue` (404), `InvalidName` (400, a
    project, story, issue or branch `Ids` or the branch check refuses), `NotACloneUrl` (400, also a URL whose last
    part is no repository name), `TooManyProjects` (409), `WorkflowMissing` (502), `CredentialRefused` (502),
    `Unreachable` (504), `Unreadable` (502). Never a credential or a local path in the text: the store's messages
    have the cache directory and the home directory replaced before they leave it.
- **`store/api`:** `Workflow` carries which states are closed; `Story` carries whether its state was stated. The store
  already gives the state as the stories index does (a stated one wins, else `Workflow.storyState`), so the planner
  keeps no second copy of the rule.
- **`store/git`:** `FileProjectLock` keeps its process lock per lock file, shared by every instance, and releases
  everything on any failure, so a replaced store and its successor never meet `OverlappingFileLockException`;
  `ProjectFile` checks the own repository's name with `Ids` before it fetches; refusals scrub local paths.
- **`app`:** a `Projects` registry (project name -> its `GitStore`, made from `ProjectFile.repositories` over the
  cache directory), controllers that take `board()` once per request and read revision, stories, issues and
  sub-issues from that one value, the state kinds from `WorkRules.check`, and one exception handler mapping each
  refusal to its problem. Every path stays behind the access token the app already checks.
- **Concurrent use:** cold `GET`s of one project wait on the project's lock and the first finds the board the first
  load made; the planner is one person's, so 8 projects with `parallel` git calls each bound the processes.
- **Red first:** `ApiMatchesDescriptionTest` reads the description and the controllers' mappings and fails on a path
  or method one has and the other lacks (seen red with one path left out); `BoardReadsTest` over a fake store: each
  read's fields and revision, `stateGiven` both ways, the kinds, `404` before a `PUT`, each refusal as its problem
  with no path or credential in it; `FileProjectLockTest`: two instances on one directory in one process, the
  second waits instead of throwing (red today); `ProjectsTest`: a new body clears the old clones, a failed one
  leaves the project unknown, the ninth project refused; `WorkRulesTest`: a rule on a closed state is dropped.
- **Plan reading:** read by another model; its findings are in this plan. Declined: a check that `project.yml`'s
  project name equals `{project}` (the library does not read the name, and a new body clears the cache, so nothing
  mixes); restricting the own URL's host before the fetch (every caller holds the access token, so a caller is the
  person, fetching with the person's own login).
- **Code reading:** read by another model; every finding is fixed with a test seen red, but two. Declined: a check
  of the repository names `project.yml` lists (the library refuses every name outside the forge's own name pattern
  before the planner sees it); a bound on the cache directories failed `PUT`s leave behind (only the person, who
  holds the access token, can make them, in their own cache).

## Acceptance

- The red-first tests seen red, then green; the native executable answers each read over a fixture store.
