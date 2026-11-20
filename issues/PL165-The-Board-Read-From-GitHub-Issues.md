# PL165 — The Board Read From GitHub Issues

```json
{
  "$schema": "issue-1.json",
  "id": "PL165",
  "title": "The Board Read From GitHub Issues",
  "state": "Backlog",
  "size": "M",
  "priority": "High",
  "rank": 81,
  "blockedBy": ["PL164", "PL125"],
  "covers": "A project whose project.yml names GitHub Issues as its tracker is opened as a GitHub store behind the planning store interface: its board is read with the person's own GitHub access through the forge clients' project items, its workflow and work rules still from the project's own repository, and a refused or unreachable GitHub stops the board with the reason"
}
```

**Why this size.** M, not L: the board, dialogs and hooks already sit behind `PlanningStore`, and the GitHub client
brings the queries, records and rate limits, so what is left is one store class over the mapping, the choice of
store when a project opens, and the token handed to the client; M rather than S because it spans `store/git` (the
choice), a new module and the native image.

**What must be true.** A person whose project keeps its planning in GitHub Issues sees the board in about a second,
however many repositories it has, with exactly what GitHub holds.

## Plan

- Today `StoreConfiguration.projects` (`planner/app`) opens every project with `GitProjects.open(project, url,
  branch)`; `GitStore` reads `project.yml`, `stories/workflow.yml`, the work rules and every repository's
  `issues/` from fetched clones (`StoreReader`).
- `project.yml` gains `tracker: {kind: github, project: <owner>/<number>}`, top level, snake_case like its other
  keys; without it the Git store opens as today. Porelo refuses a key it does not know, so a project carries it
  only once its Porelo accepts it. `GitProjects` reads it and hands the project to an opener of `store/github`
  (no Git there: the project's files are read by the Git store's own reader and handed in, so only `store/git`
  knows Git).
- `ItemsBoardReader`, behind [PL164](README.md#pl164)'s `GitHubStore`: `refresh()` reads every page of the forge
  clients' `projectItems(org, number, after)`, each item with its issue, sub-issues, blocked-by and field values,
  bounded like the Git store (the most items, the longest body), and `IssueMapping`
  ([PL164](README.md#pl164)) makes the `Board`. The store holds no query text and no records of its own.
  `Revision` is a digest of the items' `updatedAt` and the project files' commit. `textFormat()` is MARKDOWN;
  `storyState` uses the workflow as the Git store does.
- Access: one fine-grained token, the person's existing Git token in Porelo's vault, which gains Issues and
  organization Projects rights beside Contents; there is no separate planning token. It is taken as the Git
  store's lent login takes it, from Porelo's credential helper, `<porelo> vault credential --for <https address>`
  through `PORELO_EXECUTABLE` (`LentLogin`), so the window sends no token and no task holds one; it passes only
  through the helper's pipe and the client's call, never a command line or a log. That lending is switched on by
  [PL125](README.md#pl125). A missing or refused token is `CredentialRefused` and an unreachable GitHub
  `Unreachable`, as for Git, GitHub's message scrubbed of the token; the window's own refusal for it is
  [PL169](README.md#pl169).
- Red first: `GitHubStoreTest` over the forge clients' recorded answers: a story with issues in three
  repositories over two pages becomes the board [PL164](README.md#pl164)'s fixture names; a 401 gives
  `CredentialRefused`; a page beyond the bound gives a fault, not a cut board. Then one live check against the
  test organization, measured against the 0.8 s the story names.

## Order and files

Built in this order, each pushed once what it needs is published: [PL164](README.md#pl164)'s types;
[PL169](README.md#pl169)'s contract (independent, at once); then [PL165](README.md#pl165), [PL125](README.md#pl125)
followed by [PL166](README.md#pl166), and [PL169](README.md#pl169)'s behavior followed by [PL167](README.md#pl167),
side by side; then [PL168](README.md#pl168). Each builds against the forge clients' published surface with fakes of
its own.

- Owns `store/github`: `ItemsBoardReader` (implements `BoardReader` over `projectItems`), `GitHubStoreOpener`;
  `store/git`: `ProjectFile` and `GitProjects` (the `tracker` key and the choice of store); `planner/app`:
  `StoreConfiguration` (the opener and the `TokenSource` from [PL125](README.md#pl125)'s lent login). Tests:
  `ItemsBoardReaderTest`, `GitHubProjectsTest`.

## Acceptance

- The board of a test project with a story and sub-issues in three repositories loads from GitHub, measured, with
  the time and the number of calls.
- A project without `tracker` opens as a Git store unchanged (the existing store tests stay green).
- The native executable reads the test project.
- On the VM, the board is read with the token `ForgeTokens` takes from Porelo's vault, the same token Git gets
  through Porelo's helper; the log and the process list show no token.

## To be checked

- The rights a fine-grained token needs, measured in the test organization: Issues (read and write) and Contents on
  the repositories, Projects (read and write) on the organization; whether sub-issues and dependencies need more.
- Whether the forge clients ship the native-image metadata for their records, or the planner registers them.
- Whether the project's files are read faster through GitHub's contents API with an ETag than through a fetch.
