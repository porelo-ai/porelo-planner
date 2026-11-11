# PL59 — The Work Tools Run From The Planner

```json
{
  "$schema": "issue-1.json",
  "id": "PL59",
  "title": "The Work Tools Run From The Planner",
  "state": "Idea",
  "size": "-",
  "priority": "High",
  "rank": 1,
  "blockedBy": [],
  "covers": "A repository's build indexes and checks its issues and stories with a command line of the planner, so no repository needs the build tools for its own work"
}
```

**What must be true.** Every repository builds its issue and story indexes and checks them with a command line the
planner publishes, built from the Git store's own code, with the same results and exit codes as the build tools'
`issues` and `stories` commands give today; a repository changes only which artifact its build runs.

## Plan

- The planner publishes this command line, and every repository's build runs it in place of the build tools'
  commands.
- A module of its own beside the store, published to Maven Central, runnable as `exec:java` the way a repository runs
  the build tools today: `issues index`, `issues check`, `stories index`, taken from `store/git`'s own index and
  check code, never a second copy of it.
- Red first: an issue tree the index refuses gives the same fault lines and exit code from the planner's command as
  from the build tools' today, read from a fixture both run against.
