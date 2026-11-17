# PL163 — A Discussion Names the Repositories as the Planner Takes Them

```json
{
  "$schema": "issue-1.json",
  "id": "PL163",
  "title": "A Discussion Names the Repositories as the Planner Takes Them",
  "state": "Backlog",
  "size": "S",
  "priority": "High",
  "rank": 79,
  "blockedBy": [],
  "covers": "The order of an idea discussion lists each repository by the name the idea's issues[].repository must carry, beside the folder the task really reads it from; the idea format allows only those names; a refused name is said with the names allowed"
}
```

**Why this size.** S: `IdeaDiscussion.brief` (`planner/core/.../IdeaDiscussion.java`, around line 84) writes
`/workspace/<name>/` from `board.repositories()`. Those names are the last part of each central address
(`stories/Project.java`, around line 283: `porelo-pi`). The daemon mounts the repositories by their key in
`project.yml` (`/workspace/pi`). The brief, the format and the refusal change. Unit tests.

**What must be true.** An agent that writes down an idea knows the exact repository names the planner takes, and finds
each repository where the order says it is.

## Found

On 2026-11-17, in a live test. A discussion's idea was refused twice: "issues[0].repository: The repository is no
repository of the project". The order listed `/workspace/porelo-pi/` and so on, which do not exist; the task had
`/workspace/pi/`. The agent wrote the folder name, and the planner wanted `porelo-pi`. Only with the name given by hand
in the idea did it go through.

## Plan

- Today: the brief lists `/workspace/<planner name>/` only. `idea-1.json` says "One of the project's repositories"
  with a pattern, and `NewIdeas` (around line 155) refuses with no name.
- Change: the brief lists each repository as "`porelo-pi`, read at `/workspace/pi/`", taking the folder from the
  repository's key in `project.yml`. The schema handed to the agent carries the names as an `enum`. The refusal names
  the value it got and the names allowed.
- Red first: a test of the brief for a project whose key and central name differ. Today it names
  `/workspace/porelo-pi/`; after the change it names `porelo-pi` and `/workspace/pi/`. A test of the refusal's words.

## Acceptance

- The order names each repository's planner name and its real folder.
- The format the agent gets allows only the project's names.
- A refused name is said with the names allowed.
- The tests above fail without the change.
