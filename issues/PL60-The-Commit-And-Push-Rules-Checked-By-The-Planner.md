# PL60 — The Commit And Push Rules Checked By The Planner

```json
{
  "$schema": "issue-1.json",
  "id": "PL60",
  "title": "The Commit And Push Rules Checked By The Planner",
  "state": "Idea",
  "size": "-",
  "priority": "High",
  "rank": 1,
  "blockedBy": [],
  "covers": "The rules on commit messages and pushes are checked by the planner's command line, so the build tools hold none of the work format"
}
```

**What must be true.** Every repository's build checks its commits and its pushes to `main` with the planner's command
line, with the same rules, fault lines and exit codes as `check-commits` and `check-pushes` give today, and the stage
records in `stats/` by the format the planner writes.

## Plan

- The checks and their tests taken over into the command line of the planner's work tools, with the list of names
  and phrases that may not give credit; the token for reading a repository's pushes is read from the environment
  only and never printed, as today.
- The stage-record check reads the format the planner owns, a person's move on the board among its records.
- Red first: a commit whose subject is 73 characters and one naming an issue number are refused with the same fault
  lines as today, from a fixture repository.
