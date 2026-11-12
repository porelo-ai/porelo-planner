# PL102 — The Executable Published On Every Push

```json
{
  "$schema": "issue-1.json",
  "id": "PL102",
  "title": "The Executable Published On Every Push",
  "state": "Backlog",
  "size": "M",
  "priority": "High",
  "rank": 30,
  "blockedBy": [],
  "covers": "Every push to main that builds publishes the planner's native executable under a version of its own, never overwritten, with its sha256 beside it, so a repository's CI can pin a change before a release; a rule of its own removes old builds nobody pins"
}
```

**Size:** M - the Build's publish step and a clean-up workflow, and the published path is a contract every
repository's CI reads.

**Estimate:** 67 min, the measured median of M.

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
  - The version of a build from a push is its package version, `x.y.z~snapshot.<run>`, which already differs per run
    and is the version the executable names. A second string for the same build would be a copy that can disagree.
    The path refuses a version that already exists, as a release's does, so nothing is overwritten.
  - A workflow of its own, scheduled and runnable by hand, removes the snapshot builds that fall outside the newest
    N and that no repository pins. It never touches a release. It deletes only paths under
    `files/porelo-planner/` whose version has the snapshot form, and it names each one it removes.
  - `doc/` names the path and how to pin a build from a push.
- **Tests, by level.**
  - Workflow, local: the publish and read-back steps run against the local HTTPS test server, in the cases the
    release publish was tested in: a first publish, a second publish of the same version (refused), and a digest that differs (refused).
  - The clean-up's choice of what to delete is a small script over a listing. It is tested on fixtures: newest N
    kept, a pinned build kept, a release never chosen, and a listing that cannot be read fails closed. Harm: a pinned
    executable deleted under a repository's CI.

## Acceptance

- After a push to main, the run's summary names the version, path and sha256 of the published executable. A
  download of that path matches the digest. Publishing the same version again is refused.
- The clean-up keeps the newest N, every pinned build and every release, and deletes the rest. Each new test was
  seen red.

## To be checked

- Where the clean-up learns which builds are pinned: the repositories' workflows name the pin, and the clean-up
  needs read access to them, or a list the repositories keep.
- N, and whether the `files` repository's permissions let the CI's account delete there.
- `~` in an Artifactory path, which a download URL must take as it is or as `%7E`.
