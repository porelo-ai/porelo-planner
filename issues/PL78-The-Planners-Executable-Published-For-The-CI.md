# PL78 — The Planner's Executable Published For The CI

```json
{
  "$schema": "issue-1.json",
  "id": "PL78",
  "title": "The Planner's Executable Published For The CI",
  "state": "Idea",
  "size": "-",
  "priority": "High",
  "rank": 1,
  "blockedBy": [],
  "covers": "The Build publishes the planner's bare native executable with its sha256 beside it, so every repository's CI downloads it pinned by version and digest to check its issues, stories, commits and pushes"
}
```

**What must be true.** A repository's workflow downloads the planner's native executable with `curl -L`, checks it
against the sha256 published beside it and runs `porelo-planner issues check issues` with it; nothing of the planner
is published as a library.

The command line stands in the executable: `issues index|check` and `stories index|check|cut`, with the build tools'
output and exit codes. Its place is the generic Artifactory repository `files`, readable without credentials:
`files/porelo-planner/<version>/porelo-planner-<version>-linux-x86_64`, releases only, uploaded with its SHA-1 and
SHA-256, which a CI step pins together with the version; no snapshot clean-up reads that repository.

- The Build of a release publishes the executable there; the publishing token may write to `files`.
- This repository's own hand-over checks `issues index` and `issues check` run with the downloaded executable.
