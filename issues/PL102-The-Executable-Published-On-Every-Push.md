# PL102 — The Executable Published On Every Push

```json
{
  "$schema": "issue-1.json",
  "id": "PL102",
  "title": "The Executable Published On Every Push",
  "state": "Review",
  "size": "M",
  "priority": "High",
  "rank": 30,
  "blockedBy": [],
  "covers": "Every push to main that builds publishes the planner's native executable under its package version, never replaced, with its sha256 beside it, so a repository's CI can pin a change before a release"
}
```

**Size:** M - the Build's publish and read-back steps, and the published path is a contract every repository's CI
reads.

**Estimate:** 67 min, the measured median of M.

**What must be true.** A repository's workflow can pin a planner executable built from any push to main, by version
and sha256, the same way it pins a release. A published build is never replaced.

## Plan

- **Before, read in the code.** `.github/workflows/build.yml` published the bare executable to
  `files/porelo-planner/<version>/porelo-planner-<version>-linux-x86_64` on a release only, and the workflow's
  `VERSION` was the bare `project.version`, so a snapshot would have landed at `x.y.z-SNAPSHOT` on every run.
- **The change, as built.**
  - The publish and read-back steps run on every push to main that builds, as on a release.
  - The executable takes its packages' version, read from the built `.deb`'s name: `x.y.z~snapshot.<run>` for a
    snapshot, `x.y.z` for a release. The build computes it once; the step only reads it. It must belong to the
    project version on its channel and to this run: exactly `x.y.z~snapshot.<this run's number>` for a snapshot,
    exactly the tag's `x.y.z` for a release. Otherwise the step stops before uploading.
  - The existence check covers a snapshot's executable too: a version that is published already stops the run.
    Run numbers do not repeat, so a rerun after the executable was published stops there and the next push
    publishes anew.
  - `doc/index.md` names the path and how to pin; the changelog entry says snapshots too.
- **The clean-up of old builds is split off** into [PL103](README.md#pl103), since the rule it needs binds every
  repository's CI.
- **Tests, workflow level, local.** The publish and read-back steps, taken from `build.yml` as they stand, ran
  against a local HTTPS test server:
  - a snapshot published and read back under `0.1.1~snapshot.36` (red on the workflow before: nothing published);
  - the same snapshot again, refused as published already;
  - a stored file that differs from the built one, refused by the read-back;
  - a release published under `0.1.1`;
  - a package of another version, a malformed package version, a release package not of the tag's version, a
    snapshot package of another run and a release with a snapshot-shaped version, each refused before any upload
    (the last two red before the fix).

  Harm: a pin naming an executable that was replaced, or a snapshot pinned under a path every run overwrites.

- **The code reading's findings (Codex).**
  - Fixed: a snapshot's version is bound to the run's number; a release's version must be plain `x.y.z`.
  - Left to the storage: two runs of one recreated release tag could both see the path absent and both upload. Only
    a publishing token that cannot overwrite closes that, which is under *To be checked*.

## Acceptance

- After a push to main, the run's summary names the version, path and sha256 of the published executable. A
  download of that path matches the digest. Publishing the same version again is refused.

## To be checked

- That the publishing token cannot overwrite in `porelo-files`: a second PUT of the same path is
  refused by the storage too.
- That an Artifactory path holding `~` is fetched as written; the first push after this change shows it.
