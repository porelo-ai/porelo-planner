# PL179 — A Minimal Local Directory Store

```json
{
  "$schema": "issue-1.json",
  "id": "PL179",
  "title": "A Minimal Local Directory Store",
  "state": "Analyzed",
  "size": "L",
  "priority": "High",
  "rank": 2,
  "blockedBy": ["PL168"],
  "covers": "A person without a tracker, with only a local forge, keeps a project's stories and issues in a plain local directory of Markdown items outside the repositories, chosen in project.yml as the tracker, with the same fields, stage entries and velocity as a GitHub item"
}
```

**Why this size.** L, not M: a third implementation of the store API - board, moves, writes, new issues and ideas,
stage results, change detection - with its own ids and its tracker kind in `project.yml`, a contract Porelo checks;
not XL, since it takes the GitHub store's item shape (fields, body sections, stage entries) and the core's flow as
they are, and leaves out what only a forge has.

**What must be true.** A person who keeps no project in a tracker still plans with the window: stories and issues
live in one directory on their computer, outside every repository, and nothing about them is committed.

## Plan

- **Where**: `project.yml` names the tracker `local`, with no address; the items of a project lie in
  `$XDG_DATA_HOME/porelo-planner/items/<project>/` (`%LOCALAPPDATA%` on Windows, through `PrivateFiles`), private to
  the account. The directory may be a Git repository; the planner neither needs nor uses one. How `project.yml`
  names it is agreed with Agent Core.
- **An item** is one file `<repository>-<number>.md`: a JSON head (`local-item-1.json`) holding the kind (story or
  issue), the title, the repository, `Status`, `Priority`, `Size`, `Risk`, `Worker`, `Rank`, the parent, a story's
  ordered sub-issues, `blockedBy`, and the number fields `Estimate`, `Minutes`, `VM minutes`, `Tokens`, `Stages`,
  `Started`, `Ready` ([PL177](README.md#pl177)); below it the body's sections as `BodySections` writes them for
  GitHub, and the stage entries in a section of their own, each the JSON of a stage comment (`StageComment`), oldest
  first. The id is `<repository>#<number>`, as on GitHub; the number is the repository's next, taken by creating
  the file with `link(2)` so two writers never share one.
- **The store**: `store/local`, `LocalDirectoryStore implements PlanningStore`, beside `store/github`: `board()`
  reads every item file through `PrivateFiles`' checks (no link, the account's own); `save`, `createIssue`,
  `createIdea` and `saveResult` write each changed file through a temporary file and an atomic move under the
  project's `FileProjectLock`, the revision being the digest of the files read; `poll()` compares the directory's
  file names, sizes and modification times and reads again only on a change. A fault of one file leaves that item
  out and says why, as the GitHub store does.
- **Velocity**: the fields are written at a stage's save as on GitHub, and [PL177](README.md#pl177)'s figures read
  them the same way; no `stats/`.
- Red first: `LocalDirectoryStoreTest` over a temporary directory - a board read, a move written and read back, two
  writers taking numbers at once getting two, a stage saved twice counting once, a linked or foreign file refused, a
  change by hand seen by `poll()`; a `project.yml` test of the tracker `local`.

## Acceptance

- The native planner on the test VM plans a project whose `project.yml` names the tracker `local`: the board, a
  move, a new issue and a stage result, each seen in the directory's files; no repository gains a commit.

## To be checked

- How `project.yml` names the tracker `local`, with Agent Core.
