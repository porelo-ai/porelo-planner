# PL03 — The Store Read From Git

```json
{
  "$schema": "issue-1.json",
  "id": "PL03",
  "title": "The Store Read From Git",
  "state": "Review",
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

**How it stands.** Built and tested: the store's interface and records, safe clone URLs and `git` calls, the
bare clones in the person's own cache, the board answered from memory with only moved repositories fetched, the
project's repositories from its `project.yml`, and the workflow, stories, issue heads and index rows read with
`porelo-stories`, a story's state by its rule. Measured on copies of the project's twenty repositories served over
local HTTPS: 312 stories and 1607 issues, one per index row, read in about 13 s from empty clones; a check with
nothing moved in under a second.

## Plan

- **`store/api`:** `PlanningStore` with `board()` -> `Board(revision, workflow, stories, issues)`, `story(id)`,
  `issue(id)`, `refresh()`; records `Workflow(states, phases)`, `Story(id, title, state, priority, issues,
  description, link)`, `Issue(id, repository, title, state, size, blockedBy, parent, link, text)`; refusals as
  typed exceptions (`NoSuchStory`, `NoSuchIssue`, `WorkflowMissing`, `CredentialRefused`, `Unreachable`). The
  revision is opaque to every caller.
- **`store/git`**, modelled on the stories-git reader: one clone per repository under
  `$XDG_CACHE_HOME/porelo-planner/<project>/`, made and fetched with the `git` command line, so the person's own
  credential helper and SSH keys apply. Reads run on the fetched commit, never on a work tree a person edits.
  - `stories/workflow.yml` (states, phases) and each story's JSON head read with `porelo-stories`; a story without
    a stated state takes the one `Workflow.storyState` gives, the stories index's own rule.
  - each issue file's JSON head read with `porelo-stories`' `IssueHead`; an issue whose file is gone (finished or
    rejected, its row kept) read from its row in `issues/README.md` or a group's index with `IssueIndexes`, one
    page at a time, so there is no second parser; a repository with neither issues nor an index adds none and needs
    no workflow.
  - an issue's JSON head read with Jackson; the planner reads no `**Status:**` lines, and an issue without a JSON
    head is a fault named in the answer.
  - the revision is the set of the fetched commit ids.
- **Which repositories:** the window names the project's own repository (its clone URL, a branch only when Porelo
  reports one, else the remote's default branch); its `project.yml`, read with `porelo-stories`' `Project`, names
  the others and their clone URLs (`central`), each checked as a clone URL; the file must name the window's
  repository as its own, and a bound limits how many it may name. `Project` takes only clone URLs under
  `github.com/porelo-ai`, so a project on another Git server is refused until the library takes any.
- **Safety, taken from stories-git:** paths contained in the clone (no `..`, no links out), a size bound per file,
  `GIT_CONFIG_GLOBAL` and the system config honoured but `core.hooksPath` and `protocol.file.allow` refused for
  the planner's own clones, no credential on any command line, error output scanned so no secret reaches a log.
  The Git store's tests serve their fixtures over HTTPS through `git http-backend`, with a certificate made for the
  run; the secret scan is part of the Git runner.
- **No prompt, ever (fail closed):** every `git` call runs with `GIT_TERMINAL_PROMPT=0`, `SSH_ASKPASS` unset,
  `GIT_ASKPASS=/bin/false` (unset, the person's `core.askPass` would apply), `SSH_ASKPASS_REQUIRE=never` and `ssh -o BatchMode=yes`, so a missing or expired login never
  waits on a terminal or opens a dialog: it answers `CredentialRefused` at once, and the window says which
  repository needs a login. The person's ssh-agent and credential helper apply as they are.
- **What the plan's reading added** (what a caller, a `project.yml` or a remote could do):
  - clones are bare and every file is read from the fetched commit's tree by its commit id (`git cat-file`), so no
    work tree, no `.gitattributes` filter and no link exists to follow; a tree entry that is a link is refused.
  - only `https://`, `ssh://` and `user@host:path` URLs are taken, without user info, without a leading `-` or a
    control character; a branch is checked with `git check-ref-format --branch`; `--` precedes every positional
    argument; `-c protocol.allow=never -c protocol.https.allow=always -c protocol.ssh.allow=always`,
    `-c core.hooksPath=/dev/null -c core.fsmonitor=false`. A private or loopback address stays allowed: a team's
    own Git server is normal, and the person names it themselves.
  - `GIT_SSH`, `GIT_SSH_COMMAND`, `GIT_CONFIG_PARAMETERS`, `GIT_CONFIG_COUNT`, `GIT_EXEC_PATH`, `GIT_DIR` and
    `GIT_EXTERNAL_DIFF` are removed from the environment; standard input is `/dev/null`, `GCM_INTERACTIVE=never`,
    `ssh -o ConnectTimeout`, and each `git` call has a hard time limit that kills it and answers `Unreachable`.
  - the project's and each repository's name, and a story's or issue's id, match a strict pattern before they reach
    a path; the cache directory is the owner's alone (`0700`), with no link on its path.
  - bounds: repositories per project, bytes per file, bytes of `git` output kept, and YAML nesting and aliases.
  - `refresh()` runs one at a time per project (a lock in the process and a file lock in the cache, the same lock a
    save holds); the board is
    one immutable snapshot swapped whole, and `story(id)` and `issue(id)` answer from that same snapshot.
- **The board stays in memory; the remotes are only asked whether they moved:**
  - a request is answered at once from the board in memory, never after a network call; when the last check is
    older than a set interval (10 s by default), one check starts in the background, and requests meanwhile share
    it instead of starting their own.
  - a check asks each repository's remote for its branch's commit id alone (`git ls-remote`), all repositories at
    once on virtual threads with an upper bound; only a repository whose id moved is fetched and read again. What
    was read is kept per repository by its commit id, so an unmoved repository goes into the new board as it was.
  - a changed board replaces the old one whole and is announced to listeners, which the API passes on as an event;
    after a restart, the board is first read from the clones on disk and checked afterwards.
  - the check, the per-repository memory and the swap are plain Java behind a `Remote` interface, proven by unit
    tests over a fake remote: an unmoved remote is not fetched, a moved one is, two requests during a check start
    one check, and a reader during a swap sees the old board or the new one, never a mix.
- **Red first:** `GitStoreReadTest` over a fixture served by the test server: a story with two issues and a
  sub-issue reads with the right states; a link out of the clone is refused; a refused login, and one that would
  prompt, read as `CredentialRefused` at once, with no credential in its text.

- **The code reading** (another model, against `security-audit`, `concurrency-review`, `java-code-review`, `graal`,
  `test-quality`, `spring-boot-patterns`): an unforeseen failure of a background check escaped without being told,
  fixed with a test seen red. Declined: Git's error text echoing a token in a form the scrub does not know - Git 2.53
  removes the login from a URL in its own messages (measured with a token put in by `insteadOf`), and the scrub
  removes any URL's user info and an `Authorization` header whatever the token's form; a refusal when a directory on
  the cache's path cannot be checked - failing closed is meant; many `git` processes at once - each check runs at
  most `parallel` of them over a bounded number of repositories.

## Acceptance

- The red-first tests seen red, then green; the board of a copy of the real stories and indexes read in full.
- No test needs the network.
