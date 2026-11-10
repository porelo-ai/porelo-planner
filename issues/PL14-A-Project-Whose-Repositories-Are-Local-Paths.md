# PL14 — A Project Whose Repositories Are Local Paths

```json
{
  "$schema": "issue-1.json",
  "id": "PL14",
  "title": "A Project Whose Repositories Are Local Paths",
  "state": "Idea",
  "size": "-",
  "priority": "Low",
  "rank": 2,
  "blockedBy": [],
  "covers": "Planning a project whose own repository and project.yml repositories are absolute local paths, such as Porelo's Default project or a local checkout"
}
```

**What must be true.** A person plans a project whose repositories Porelo names by an absolute local path rather
than a clone URL, as Porelo's Default project and a local checkout do; today the planner refuses such a project as
`NotACloneUrl`, and the window offers no planning for it.

## Open questions

- Whether local projects get planning at all; taking them needs the library that reads `project.yml` to accept a
  local path as a repository's `central`, beside the planner's own clone-URL check.
