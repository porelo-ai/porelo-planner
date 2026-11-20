# PL165 — The Board Read From GitHub Issues

```json
{
  "$schema": "issue-1.json",
  "id": "PL165",
  "title": "The Board Read From GitHub Issues",
  "state": "Analyzed",
  "size": "L",
  "priority": "High",
  "rank": 81,
  "blockedBy": ["PL164", "PL125"],
  "covers": "A project whose project.yml names GitHub Issues as its tracker is opened as a GitHub store behind the planning store interface: its board is read with the person's own GitHub access in one GraphQL query per page, its workflow and work rules still from the project's own repository, and a refused or unreachable GitHub stops the board with the reason"
}
```

**Why this size.** L: a new store module beside `store/git`, the choice of store when a project opens, the person's
access with a new refusal in the window's contract and the screen's offer beside it, the GraphQL reading with paging and bounds, native-image metadata, and calls that porelo-forge-clients's
GitHub client must offer first.

**What must be true.** A person whose project keeps its planning in GitHub Issues sees the board in about a second,
however many repositories it has, with exactly what GitHub holds.

## Plan

- Today `StoreConfiguration.projects` (`planner/app`) opens every project with `GitProjects.open(project, url,
  branch)`; `GitStore` reads `project.yml`, `stories/workflow.yml`, the work rules and every repository's
  `issues/` from fetched clones (`StoreReader`).
- `project.yml` gains `tracker: {kind: github, project: <owner>/<number>}`; without it the Git store opens as today.
  `GitProjects` reads it and hands the project to an opener of `store/github` (no Git there: the project's files are
  read by the Git store's own reader and handed in, so only `store/git` knows Git).
- `GitHubStore implements PlanningStore`: `refresh()` reads the GitHub project's items with their fields, sub-issues,
  dependencies and bodies in one GraphQL query, paged by 100, bounded like the Git store (the most items, the
  longest body); `IssueMapping` ([PL164](README.md#pl164)) makes the `Board`. `Revision` is a digest of the items' `updatedAt` and
  the project files' commit. `textFormat()` is MARKDOWN; `storyState` uses the workflow as the Git store does.
- Access: the person's GitHub token is taken as the Git store's lent login takes it, from Porelo's credential
  helper, `<porelo> vault credential --for <https address>` through `PORELO_EXECUTABLE` (`LentLogin`), so the
  window sends no token and no task holds one; it passes only through the helper's pipe, never a command line or a
  log. That lending is switched on by [PL125](README.md#pl125). Where the vault holds no token for the address, or
  GitHub refuses it for a missing right, the board is refused with a new code `NoForgeAccess` in `planner.yaml`
  (fields `address`, and `right` where GitHub names it, such as `Issues` or `Projects`), so the window can offer
  to put a token into the vault and read the board again. The screen (`screen/lib/src/hooks.dart`) adds
  `Future<bool> handOnForgeToken(String address)` to `PlanningHost`, which the window implements, offers it beside
  that refusal, and reads the board again after `true`; a widget test shows the offer for the refusal and none for
  another; `Unreachable` as for Git, GitHub's message scrubbed of
  the token.
- Calls go through porelo-forge-clients's `GitHubGraphQl` with the token per call; this module only holds the query
  text and the records.
- Red first: `GitHubStoreTest` over recorded GraphQL answers (the forge clients' fixture recorder): a story with
  issues in three repositories becomes the board [PL164](README.md#pl164)'s fixture names; a 401 and a 403 naming a missing right give `NoForgeAccess`; a page
  beyond the bound gives a fault, not a cut board. Then one live check against the test organization, measured
  against the 0.8 s the story names.

## Acceptance

- The board of a test project with a story and sub-issues in three repositories loads from GitHub in one query
  per page, measured, with the time.
- A project without `tracker` opens as a Git store unchanged (the existing store tests stay green).
- The native executable reads the test project (reflection metadata for the GraphQL records).

## Open questions

- Which GitHub access the person gives: a fine-grained token kept in Porelo's vault (repository issues read and
  write, organization projects read and write), or GitHub's own sign-in of the person through a GitHub App the
  project registers.

## To be checked

- The rights a fine-grained token needs, measured in the test organization: Issues (read and write) and Contents on
  the repositories, Projects (read and write) on the organization; whether sub-issues and dependencies need more.
- With Agent Gorge: the GraphQL query of a project's items with fields, sub-issues and dependencies, and the error
  and rate-limit answers, offered by `GitHubGraphQl`.
- Whether the project's files are read faster through GitHub's contents API with an ETag than through a fetch.
