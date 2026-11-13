# PL103 — Old Snapshot Executables Cleaned Up

```json
{
  "$schema": "issue-1.json",
  "id": "PL103",
  "title": "Old Snapshot Executables Cleaned Up",
  "state": "Review",
  "size": "M",
  "priority": "Medium",
  "rank": 31,
  "blockedBy": [],
  "covers": "A clean-up removes the planner's old snapshot executables from Artifactory by a rule, and never one a repository pins, a release or anything outside its prefix"
}
```

**Size:** M - a clean-up workflow and its script over listings, and a pinning rule every repository's CI follows.

**Estimate:** 67 min, the measured median of M.

**What must be true.** Old snapshot executables of the planner go away by a rule, and none that a repository pins
goes with them.

## Plan

- **The clean-up**, `.github/workflows/cleanup.yml`, runs on a schedule (Mondays) and by hand, and only from `main`,
  with a token of its own, secret `JF_CLEANUP_TOKEN`, that may delete only under `porelo-files/porelo-planner/`; the
  publishing token cannot delete. It keeps curl's rules from the Build: the token on standard input, no redirect
  followed with it, only a 200 a listing and a 2xx a delete.
  - It deletes a snapshot build only when it is older than 30 days, not among the newest 10, and named by no pin.
    Never a release, never a path outside the prefix, and never one whose version is not `x.y.z~snapshot.N`.
  - A repository that sets or moves its planner pin takes a build younger than 7 days; an existing pin may stay. A
    build is then never pinned anew within weeks of the clean-up taking it, so reading the pins and deleting need no
    lock between them. This is a shared rule of every repository that pins the planner.
  - The project file is `project.yml` at the root of `porelo-project`'s default branch; its repositories are
    `project.central` and every `repositories.<key>.central`, today 19 besides the project's own, each a
    `git@github.com:porelo-ai/<name>.git` address. The clean-up reads it with the stories index's own `Project.read`
    and clones each repository's default branch anonymously over `https://`, as `Project.web` gives it.
  - No repository pins the planner yet: no default branch in the local clones names `porelo-files/porelo-planner/` or
    a version `x.y.z~snapshot.N`. The pin's form is therefore set here: a pin is the build's whole version
    `x.y.z~snapshot.N`, written out anywhere on the default branch - a workflow, a script, a pom - with `~` plain or
    as `%7E`, in a file read as bytes and as UTF-16 of either byte order, or in a link's target, which is not
    followed. The clean-up keeps every snapshot version any file of a default branch names, so a pin is never missed
    for where it stands, and a version that is only mentioned is kept too, at no cost.
  - Every listing, of the builds and of each repository's pins, must be read whole and parse, or the clean-up deletes
    nothing and fails with what it could not read. It names each build it removes.
- **The choice** is plain Java in the module `cleanup`, beside the planner's parts and never shipped, so the executable
  carries no CI-only command and the workflow builds only the store and the choice: `Listing` reads Artifactory's deep
  file list, `Pins` the checkouts, `Choice` the rule, and `Main` writes its answer to a file only once all of it
  stands. It reaches no network; the workflow fetches and deletes.
- **Tests**, unit tests over listing fixtures through `Main`: the newest 10 kept, one younger than 30 days kept, a
  pinned build kept (plain, percent-encoded, in a UTF-16 script of either byte order and as a
  tracked link's target), a release never chosen, a path outside the prefix never chosen, a
  version not `x.y.z~snapshot.N` never chosen, a listing of another folder, a listing cut short, a file without a time
  and a repository without a checkout each choose nothing. Harm: a pinned executable deleted under a repository's CI.

## Acceptance

- The clean-up keeps the newest 10, every build younger than 30 days, every pinned build and every release, and
  deletes the rest; a listing it cannot read whole deletes nothing. Each new test was seen red.

## What building found

- No repository pins the planner yet, so the pin's form is set here: the whole version `x.y.z~snapshot.N` written out
  on the default branch. The shared rule says so, with the 7 days; the coordinator places it in the shared block.
- The workflow cannot run locally against Artifactory, and nothing here called Artifactory or GitHub: the listing's
  shape is Artifactory's documented deep file list, and the first run is its test. A listing of another shape, a
  repository that cannot be cloned anonymously and a missing secret each stop the run before anything is deleted.
- `check-actions` allows no `environment` on a job, so the secret cannot be bound to `main` by a GitHub environment;
  the job's `if` keeps the schedule and a dispatch on `main`, and a dispatch on another branch skips the job.

## For the operator

- A token that may delete only under `porelo-files/porelo-planner/` and read its listing, as the repository secret
  `JF_CLEANUP_TOKEN`; the publishing token `JF_ACCESS_TOKEN` keeps no delete right. Until it is set, the clean-up fails
  with "secret JF_CLEANUP_TOKEN is not set" and deletes nothing.
