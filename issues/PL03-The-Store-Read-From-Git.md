# PL03 — The Store Read From Git

**Status:** Analyzed; blocked by [PL01](README.md#pl01).
**Size:** L - the store's interface, the Git implementation reading stories, issue indexes, sub-issues and the
workflow with Jackson, the person's own Git login, and its safety tests.

**What must be true.** The planner reads a project's whole board from Git through one interface: the workflow's
states and phases, every story with its head (state, priority, issues), every issue of each repository's index with
its sub-issues, and an issue's text, each at a revision that names exactly the commits it was read from. It uses the
person's own Git login and never asks for, keeps or logs a credential.

## Plan

- **`store/api`:** `PlanningStore` with `board()` -> `Board(revision, workflow, stories, issues)`, `story(id)`,
  `issue(id)`, `refresh()`; records `Workflow(states, phases)`, `Story(id, title, state, priority, issues,
  description, link)`, `Issue(id, repository, title, state, size, blockedBy, parent, link, text)`; refusals as
  typed exceptions (`NoSuchStory`, `NoSuchIssue`, `WorkflowMissing`, `CredentialRefused`, `Unreachable`). The
  revision is opaque to every caller.
- **`store/git`**, modelled on the stories-git reader: one clone per repository under
  `$XDG_CACHE_HOME/porelo-planner/<project>/`, made and fetched with the `git` command line, so the person's own
  credential helper and SSH keys apply. Reads run on the fetched commit, never on a work tree a person edits.
  - `stories/workflow.yml` (states, phases) and each story's JSON head read with Jackson.
  - each repository's `issues/README.md` index (and its groups) read into rows with the `porelo-stories` library's
    `IssueIndexes` and an `IssueSource` over the planner's clones, the same reading that writes the stories index, so
    there is no second parser; a sub-issue `<parent>.<n>` read with
    its parent, its role from its head.
  - an issue's head read as JSON with Jackson where it has one, else from its `**Status:**` lines.
  - the revision is the set of the fetched commit ids.
- **Which repositories:** the project's `project.yml` names them; the clone URLs come from it.
- **Safety, taken from stories-git:** paths contained in the clone (no `..`, no links out), a size bound per file,
  `GIT_CONFIG_GLOBAL` and the system config honoured but `core.hooksPath` and `protocol.file.allow` refused for
  the planner's own clones, no credential on any command line, error output scanned so no secret reaches a log.
  The Git HTTP test server and the secret scan go to `test-support`.
- **Red first:** `GitStoreReadTest` over a fixture served by the test server: a story with two issues and a
  sub-issue reads with the right states; a link out of the clone is refused; a refused login reads as
  `CredentialRefused` with no credential in its text.

## Acceptance

- The red-first tests seen red, then green; the board of a copy of the real stories and indexes read in full.
- No test needs the network.

## Open questions

- Each repository's issues carry a JSON head read with Jackson; until that is the format everywhere, the planner reads
  the `**Status:**` lines too. Is reading both acceptable for a while, or does the planner wait for the JSON head?

## To be checked

- `porelo-stories` is published under LGPL-3.0-or-later before the planner links it.

- Which `project.yml` field names each repository's clone URL, and whether a repository's issue index may be split
  into groups (`issues/base/`) in every repository.
