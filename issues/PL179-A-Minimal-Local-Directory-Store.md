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

**Why this size.** L, not M: a contract change the window builds against (the directory per project), a third
implementation of the store API - board, moves, writes, new issues and ideas, stage results, change detection -
with its own ids, and its tracker kind in `project.yml`, a contract Porelo checks; not XL, since it takes the GitHub store's item shape (fields, body sections, stage entries) and the core's flow as
they are, and leaves out what only a forge has.

**What must be true.** A person who keeps no project in a tracker still plans with the window: stories and issues
live in one directory on their computer, outside every repository, and nothing about them is committed.

## Plan

- **Where**: `project.yml` names the tracker `tracker: { kind: local }`, with no other setting, a form Porelo
  already takes, so it names no path of one machine; the items of a project lie in
  `$XDG_DATA_HOME/porelo-planner/items/<project>/` (`%LOCALAPPDATA%` on Windows, through `PrivateFiles`), private to
  the account. The directory may be a Git repository; the planner neither needs nor uses one.
- **The directory is a default only**: the person may keep a project's items elsewhere, set per project in the
  window's own configuration, never in the signed `project.yml`. The window sends it with the project, as an
  optional member `items` of `planner.yaml`'s `ProjectSource` in `PUT /projects/{project}`: an absolute path, one
  line, at most 4096 characters. The planner keeps no setting of its own; it takes the path the window sent for
  as long as the project is open, and the default when none is sent. The window's part - where it keeps the setting
  per project and how the person picks the directory - is agreed with Agent Frontend.
- **A missing or moved directory**: the planner checks the directory at the `PUT` and at every read - absolute,
  a directory of the account alone, no link in its path; a set directory that is missing, or no longer the
  account's, refuses the project with `400` naming the path, and the planner never makes it, falls back to the
  default or moves items. The default directory alone is made on the first `PUT`, with mode `0700`. A directory
  moved by the person is found again only by setting the new path in the window; a `PUT` with another path opens
  the project there, as another body does today.
- **What the window needs to offer it**: the project's answer carries `tracker.kind` (`github`, `local`) and, for
  a local one, `items`, the directory the planner uses, the default included, so the window offers the setting
  only where it applies and shows where the items lie. A set directory refused gets a problem type of its own,
  `ItemsDirectoryRefused`, with `parameters.items` naming the path, so the window offers a new pick for it alone;
  an `items` sent for a project whose tracker is not `local` is refused as `ProjectRefused`. The screen's
  `PlannerProject` gains the optional `items`, sent by `_toJson` only when set and part of `==` and `hashCode`, so
  a changed directory is sent again; the window's settings, the folder picker and the re-pick are Agent Frontend's,
  against the contract as it stands when this issue is built.
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
  change by hand seen by `poll()`; a set directory that is missing, a link or another account's refused and never
  made, the default made once with mode `0700`; a `project.yml` test of the tracker of kind `local`.

## Acceptance

- The native planner on the test VM plans a project whose `project.yml` names a tracker of kind `local`: the board, a
  move, a new issue and a stage result, each seen in the directory's files; no repository gains a commit.
