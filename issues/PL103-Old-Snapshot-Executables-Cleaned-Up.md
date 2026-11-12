# PL103 — Old Snapshot Executables Cleaned Up

```json
{
  "$schema": "issue-1.json",
  "id": "PL103",
  "title": "Old Snapshot Executables Cleaned Up",
  "state": "Analyzed",
  "size": "M",
  "priority": "High",
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

- **The clean-up**, a workflow of its own, runs on a schedule and by hand, and only from `main`, with a token of its
  own that may delete only under `files/porelo-planner/`; the publishing token cannot delete. It keeps curl's rules
  from the Build: the token on standard input, no redirect followed with it.
  - It deletes a snapshot build only when it is older than 30 days, not among the newest 10, and named by no pin.
    Never a release, never a path outside the prefix, and never one whose version is not `x.y.z~snapshot.N`.
  - A repository that sets or moves its planner pin takes a build younger than 7 days; an existing pin may stay. A
    build is then never pinned anew within weeks of the clean-up taking it, so reading the pins and deleting need no
    lock between them. This is a shared rule of every repository that pins the planner.
  - The pins are read from the default branch of every repository the project file names. Every listing, of the
    builds and of each repository's pins, must be read whole and parse, or the clean-up deletes nothing and fails
    with what it could not read. It names each build it removes.
- **Tests.** The choice of what to delete is a small script over the listings, tested on fixtures: the newest 10
  kept, one younger than 30 days kept, a pinned build kept, a release never chosen, a path outside the prefix never
  chosen, and a listing that is cut short or unreadable deletes nothing. Harm: a pinned executable deleted under a
  repository's CI.

## Acceptance

- The clean-up keeps the newest 10, every build younger than 30 days, every pinned build and every release, and
  deletes the rest; a listing it cannot read whole deletes nothing. Each new test was seen red.

## To be checked

- That a delete token limited to `files/porelo-planner/` can be had.
- Where the project file lists the repositories, and that each one's pin is readable from its default branch
  without a credential.
