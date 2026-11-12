# PL102 — The Executable Published On Every Push

```json
{
  "$schema": "issue-1.json",
  "id": "PL102",
  "title": "The Executable Published On Every Push",
  "state": "Implementing",
  "size": "L",
  "priority": "High",
  "rank": 30,
  "blockedBy": [],
  "covers": "Every push to main that builds publishes the planner's native executable under a version of its own, never overwritten, with its sha256 beside it, so a repository's CI can pin a change before a release; a rule of its own removes old builds nobody pins"
}
```

**Size:** L - the Build's publish step, a clean-up workflow that reads every repository's pin, and a rule for
pinning that every repository follows.

**Estimate:** a guess: no L has a measured median yet; about twice M's 67 min.

**What must be true.** A repository's workflow can pin a planner executable built from any push to main, by version
and sha256, the same way it pins a release. A published build is never replaced. Old builds go away by a rule, and
none that a repository pins goes with them.

## Plan

- **Today, read in the code.** `.github/workflows/build.yml` publishes the bare executable to
  `files/porelo-planner/<version>/porelo-planner-<version>-linux-x86_64` on a release only. It refuses a path that
  already exists, then reads the file back anonymously and compares its sha256. A snapshot build publishes nothing
  there.
- **The change.**
  - The publish step and its read-back run on every push to main that builds, not only on releases.
  - The version of a build from a push is its package version, `x.y.z~snapshot.<run>`. It differs per run and is the
    version the executable names. Today the workflow's `VERSION` is the bare `project.version`, so a snapshot would
    land at `x.y.z-SNAPSHOT` on every run. The step takes the package version the build computed instead, the one
    value the packages and `/health` use.
  - Nothing is overwritten. Run numbers do not repeat, and a rerun of a run comes after it, never beside it. The
    existence check stays, and the storage refuses a replacement itself: the publishing token may deploy but not
    overwrite or delete.
  - **The clean-up**, a workflow of its own, runs on a schedule and by hand, and only from `main`, with a token of
    its own that may delete only under `files/porelo-planner/`; the publishing token cannot delete. It keeps
    curl's existing rules: the token on standard input, no redirect followed with it.
    - It deletes a snapshot build only when it is older than 30 days, not among the newest 10, and named by no pin.
      Never a release, and never a path outside the prefix or whose version does not have the snapshot form.
    - A repository pins only a build younger than 7 days, the newest it needs. A build is thus never pinned anew
      within weeks of the clean-up taking it, so reading the pins and deleting need no lock between them.
    - The pins are read from the default branch of every repository the project file names. Every listing, of the
      builds and of each repository's pins, must be read whole and parse, or the clean-up deletes nothing and fails
      with what it could not read. It names each build it removes.
  - `doc/` names the path, how to pin a build from a push, and the 7-day rule.
- **Tests, by level.**
  - Workflow, local: the publish and read-back steps run against the local HTTPS test server, in the cases the
    release publish was tested in: a first publish, a second publish of the same version (refused), and a digest
    that differs (refused). The snapshot path carries the package version.
  - The clean-up's choice of what to delete is a small script over the listings. It is tested on fixtures: the
    newest 10 kept, one younger than 30 days kept, a pinned build kept, a release never chosen, a path outside the
    prefix never chosen, and a listing that is cut short or unreadable deletes nothing. Harm: a pinned executable
    deleted under a repository's CI.
- **The plan reading's points**, all taken: the package version as the snapshot path; no-overwrite enforced by the
  token as well as checked; the clean-up's age rule instead of a lock; incomplete listings delete nothing; a delete
  token of its own, from `main` only.

## Acceptance

- After a push to main, the run's summary names the version, path and sha256 of the published executable. A
  download of that path matches the digest. Publishing the same version again is refused.
- The clean-up keeps the newest 10, every build younger than 30 days, every pinned build and every release, and
  deletes the rest; a listing it cannot read whole deletes nothing. Each new test was seen red.

## To be checked

- That the publishing token cannot overwrite in `files` (a second PUT of the same path is refused), and that a
  delete token limited to `files/porelo-planner/` can be had.
- Where the project file lists the repositories, and that each one's pin is readable from its default branch
  without a credential.
- That an Artifactory path holding `~` is fetched as written.
