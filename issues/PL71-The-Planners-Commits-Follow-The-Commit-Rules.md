# PL71 — The Planner's Commits Follow The Commit Rules

```json
{
  "$schema": "issue-1.json",
  "id": "PL71",
  "title": "The Planner's Commits Follow The Commit Rules",
  "state": "Backlog",
  "size": "S",
  "priority": "High",
  "rank": 1,
  "blockedBy": [],
  "covers": "A commit the planner makes that changes only issues, stories or their stage records carries [skip ci] in its subject, within 72 characters, so the planner's commits pass check-commits and start no build"
}
```

**Size:** S - the subject of the Git store's commits in `store/git` and its tests; no contract another repository
reads.

**Estimate:** 33 min, the measured median of S.

**What must be true.** A commit the planner makes that changes only a repository's issues, stories or their stage
records carries `[skip ci]` at the end of its subject, so it starts no build and holds to the shared commit rules; a
commit that changes anything else carries none.

## Plan

Read in today's code: `GitStore.commitAndPush` collects each changed repository's paths (`SaveFiles.changed`) and its
subject (`SaveFiles.subject`, at most 72 characters: "Move an issue to Backlog", "Set who works an issue",
"Add an idea"), and `GitRemote.commit` writes it with `commit-tree -m`. No subject carries `[skip ci]`. A save writes
only files under `issues/`, `stories/` and `stats/` (the stage records of moved issues).

Steps:

1. `SaveFiles.subject` takes the commit's changed paths; when every one lies under `issues/`, `stories/` or `stats/`,
   it ends the subject with ` [skip ci]` and keeps the whole within 72 characters, choosing the shorter wording first
   as today. A path elsewhere (such as `project.yml`, which a save never writes today) leaves the marker out.
2. Red first, unit in `SaveFilesTest` and `GitStoreSaveTest`: a move's commit, a worker's and a new idea's end with
   `[skip ci]`; every subject the tests produce is at most 72 characters; a commit with a path outside the three
   directories carries none (harm: a planning commit starting a full build on rented machines, or a code commit
   skipping its build).
3. The subjects are checked against `porelo-release check-commits` on a repository with one of each kind of planner
   commit, at the version the Build pins.

## Acceptance

- The planner's commits of a move, a worker change and a new idea end with `[skip ci]`, each subject at most 72
  characters, and `check-commits` passes over them. Seen failing first: today's "Move an issue to Backlog".

## To be checked

- That `check-commits` at the pinned version takes a subject ending in `[skip ci]` and the planner's commits as they
  are (no trailers).
