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
output and exit codes. Missing is its place: no Artifactory repository holds a file of the planner today (the
generic ones are the host's, the window's for Windows, the IntelliJ plugin's and a test repository).

- The Build of a push to main publishes the executable on its own, with its sha256 beside it, to the same channel and
  under the same version as the packages.
- This repository's own hand-over checks `issues index` and `issues check` run with the downloaded executable.

## To be checked

- The Artifactory repository the operator creates or names for it, and its path and published name, agreed in the
  channel with the build tools' agent, since every repository's workflow downloads it.
