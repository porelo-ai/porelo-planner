# PL78 — The Planner's Executable Published For The CI

```json
{
  "$schema": "issue-1.json",
  "id": "PL78",
  "title": "The Planner's Executable Published For The CI",
  "state": "Implementing",
  "size": "M",
  "priority": "High",
  "rank": 26,
  "blockedBy": [],
  "covers": "The Build publishes the planner's bare native executable with its sha256 beside it, so every repository's CI downloads it pinned by version and digest to check its issues, stories, commits and pushes"
}
```

**Size:** M - one step of the Build, but its path and digest are a contract every repository's CI reads.

**Estimate:** 67 min, the measured median of M.

**What must be true.** A repository's workflow downloads the planner's native executable with `curl -L`, checks it
against the sha256 published beside it and runs `porelo-planner issues check issues` with it; nothing of the planner
is published as a library.

The command line stands in the executable: `issues index|check` and `stories index|check|cut`, with the build tools'
output and exit codes. Its place is the generic Artifactory repository `files`, readable without credentials:
`files/porelo-planner/<version>/porelo-planner-<version>-linux-x86_64`, releases only, uploaded with its SHA-1 and
SHA-256, which a CI step pins together with the version; no snapshot clean-up reads that repository.

- The Build of a release publishes the executable there; the publishing token may write to `files`.
- This repository's own hand-over checks `issues index` and `issues check` run with the downloaded executable.

## Plan

- **Today, read in the code.** The Build's job *Build, packages and publish* (`.github/workflows/build.yml`):
  - *Which channel* sets `releases` for a tag `v<version>`, and `snapshots` for `main`.
  - *Publish the packages* refuses a release file that is already stored (`absent`, anonymous), then uploads the
    `.deb` and the `.rpm` with `put`. `put` sends the token through `curl -K -`, with the SHA-1 and SHA-256 headers,
    and takes only a 2xx.
  - *The packages are indexed* reads both indexes anonymously, with retries.
  - The native executable stands at `planner/app/target/porelo-planner`, already checked for x86-64 v1.
- **The change.**
  - On the `releases` channel only, *Publish the packages* also refuses an existing
    `files/porelo-planner/<version>/porelo-planner-<version>-linux-x86_64`. It then puts the executable under that
    name, with the same `put`.
  - A new step, *The executable is published*, downloads it anonymously with `curl -L`. It retries until the file
    answers or a time limit passes, then compares its SHA-256 with the built file's. A mismatch or a missing file
    fails the run, naming what it asked and what it got.
  - The step writes the version and SHA-256 into the job summary, for the pins in other repositories.
  - Snapshots publish nothing to `files`.
- **This repository's own check with the downloaded executable** is the daily issues check, which takes the released
  executable pinned by version and SHA-256.
- **Tests.** The steps are workflow shell; their logic is the upload and the check of the digest.
  - Before the hand-over, the two steps' script is run locally against a throwaway local HTTP server (stored file,
    absent file, wrong digest). Each failure path is seen once, with its message read.
  - The real run is the next release's Build.

## Acceptance

- A release Build stores the executable at the path above with both checksums. The step after it reads it back
  anonymously with the built file's SHA-256, and the summary names the version and the digest.
- A second release Build of the same version stops before any upload.
- A snapshot Build publishes nothing to `files`.

## To be checked

- Whether the publishing token may write to `files`; asked of the operator through the coordinator before the
  first release.
