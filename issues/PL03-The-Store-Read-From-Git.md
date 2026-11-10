# PL03 — The Store Read From Git

```json
{
  "$schema": "issue-1.json",
  "id": "PL03",
  "title": "The Store Read From Git",
  "state": "Implementing",
  "size": "L",
  "priority": "Medium",
  "rank": 1,
  "blockedBy": ["PL01"],
  "covers": "The store's interface and its Git implementation: workflow, stories, issue indexes and sub-issues read with Jackson (JSON heads only) and the stories library, with the person's own Git login"
}
```

**Why this size.** the store's interface, the Git implementation reading stories, issue indexes, sub-issues and the
workflow with Jackson, the person's own Git login, and its safety tests.

**What must be true.** The planner reads a project's whole board from Git through one interface: the workflow's
states and phases, every story with its head (state, priority, issues), every issue of each repository's index with
its sub-issues, and an issue's text, each at a revision that names exactly the commits it was read from. It uses the
person's own Git login and never asks for, keeps or logs a credential.

**How it stands.** Every repository's issues carry JSON heads. Reading `project.yml` and a story's head waits for
public readers in `porelo-stories`; the rest is built meanwhile.

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
  - an issue's JSON head read with Jackson; the planner reads no `**Status:**` lines, and an issue without a JSON
    head is a fault named in the answer.
  - the revision is the set of the fetched commit ids.
- **Which repositories:** the window names the project's own repository (its clone URL, a branch only when Porelo
  reports one, else the remote's default branch); its `project.yml` names the others and their clone URLs.
- **Safety, taken from stories-git:** paths contained in the clone (no `..`, no links out), a size bound per file,
  `GIT_CONFIG_GLOBAL` and the system config honoured but `core.hooksPath` and `protocol.file.allow` refused for
  the planner's own clones, no credential on any command line, error output scanned so no secret reaches a log.
  The Git HTTP test server and the secret scan go to `test-support`.
- **No prompt, ever (fail closed):** every `git` call runs with `GIT_TERMINAL_PROMPT=0`, `SSH_ASKPASS` and
  `GIT_ASKPASS` unset, `SSH_ASKPASS_REQUIRE=never` and `ssh -o BatchMode=yes`, so a missing or expired login never
  waits on a terminal or opens a dialog: it answers `CredentialRefused` at once, and the window says which
  repository needs a login. The person's ssh-agent and credential helper apply as they are.
- **Red first:** `GitStoreReadTest` over a fixture served by the test server: a story with two issues and a
  sub-issue reads with the right states; a link out of the clone is refused; a refused login, and one that would
  prompt, read as `CredentialRefused` at once, with no credential in its text.

## Acceptance

- The red-first tests seen red, then green; the board of a copy of the real stories and indexes read in full.
- No test needs the network.

## To be checked

- Which `project.yml` field names each repository's clone URL, and whether a repository's issue index may be split
  into groups (`issues/base/`) in every repository.
