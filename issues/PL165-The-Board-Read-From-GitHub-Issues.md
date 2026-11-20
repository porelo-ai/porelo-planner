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

### Plan reading

What agy's reading of this plan found, and how the build answers it:

- **Where it reads and with whose token.** The forge origin is `https://<host>` of the own repository's clone
  address, which the window gave. `kind: github` is taken for `github.com` alone. Its API base is
  `https://api.github.com/`, and its token is asked for `https://github.com`, through `TokenSource.checkedOrigin`.
  A `project.yml` whose `tracker.project` names another owner than the own repository's, or whose repositories,
  its own repository's `central` among them, stand on another host or under another owner, is `Unreadable`. So
  are a `tracker` with another `kind`, a kind that is no lower-case name, a project number below 1, or keys the
  planner does not know.
- **Read only.** Opening, `board()` and `refresh()` send no mutation: no `setFieldOptions`, no `setFieldValues`,
  no rewrite of a sum. A missing field or option, or one of the wrong type, is a fault on the board; the writes
  come only from [PL166](README.md#pl166). The `HttpClient` follows no redirect and has a connect timeout.
- **What one read reads.** `projectFields` and every page of `projectItems`, 100 items or fields to a page and at
  most 50 pages. Beyond that, or a page with more than asked for, the read is `Unreadable`, never a cut board, and
  one read has a deadline of 60 s, which its last answer must meet as well. An item without an issue (a draft or
  a pull request) is left out. An item whose issue's repository belongs to another owner is left out as a fault;
  one of the owner whose repository name the planner does not take lies outside the project's repositories, and
  GitHub's words reach a fault only in the form of an address. An item whose sub-issues or blockers run to a second
  page is left out as a fault, and its story is not whole; the mapping still knows its id, so an issue blocked by
  it or under it is left out as depending on it, never as outside the project. An item seen twice across pages
  counts once by its node id. A cursor that does not advance, or is blank, is refused. A body longer than GitHub
  takes keeps its item on the board without the body, with a fault naming the item.
- **Fields.** Every field the store uses is checked for its kind, and each single-select field (Status, Priority,
  Size, Worker, Risk) for the options the store writes; a field of another kind is named once, not again for
  each option. A later field of the same name stands for the name, and no option of an earlier one is kept.
- **Stage comments are not read on the board.** The client's board answer carries no comments, and reading them
  per issue would cost one call per issue. The board shows the number fields as GitHub holds them. The check of
  the fields against their comments ([PL164](README.md#pl164)) runs when an issue is saved, over that issue's
  comments alone. `ItemView` tells comments not read apart from none.
- **`archived` and the owner come from the client.** `ProjectItem` gains `isArchived`, and `BoardIssue` gains its
  repository's owner, asked of Agent Gorge. Until they are in, an archived item reads as live, which shows it and
  keeps its alias taken.
- **The revision.** It is a digest of the project files' commit, the board's faults, and every item's node id,
  repository, number, title, body, labels, `updatedAt`, open and archived state and its reason, parent, sub-issues,
  blockers and field values, in a fixed order, each list with its count and a missing value tagged apart from any
  text. A change of a field alone, or of a fault alone, changes it.
- **The project's files** are read again on `refresh()` from the own repository's head, as the Git store reads
  them, so a changed workflow or work rule is seen without reopening. `TrackedStores.open` takes the project read
  at opening and a source that gives it again: the Git store's source asks for the own repository's head and reads
  `project.yml`, the workflow and the work rules again only when it moved. A read that fails is the store's
  refusal, never the project read before shown as the head's.
- **Refusals.** A 401 or 403 that GitHub marks as rate limited is `Unreachable` and names the wait. Holding off
  background reads until the wait ends belongs to [PL167](README.md#pl167), which owns them. Every other refusal,
  the store's own included, follows [PL169](README.md#pl169)'s `ForgeAccessRefusals`. The token a call used is the one scrubbed from its failure:
  the reader passes it on, and no second ask is made.
- **Ids** stay [PL164](README.md#pl164)'s aliases until [PL170](README.md#pl170) makes the tracker reference the
  id.
- **Tests**: `ItemsBoardReaderTest` (two pages with field values, `projectFields` held in the snapshot's config, a
  field change that changes the revision, a draft item, an item with a second page of sub-issues, the bound, a
  non-advancing cursor, a rate limit, a token in a failure, and a fake client that fails on any mutation) and
  `GitHubProjectsTest` (the `tracker` key, another owner, another host, an unknown kind, an unknown key).

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
