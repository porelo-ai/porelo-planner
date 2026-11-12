# PL86 — The Stage Rule's Remaining Switch-Offs

```json
{
  "$schema": "issue-1.json",
  "id": "PL86",
  "title": "The Stage Rule's Remaining Switch-Offs",
  "state": "Idea",
  "size": "-",
  "priority": "Medium",
  "rank": 1,
  "blockedBy": [],
  "covers": "The commit check's stage rule cannot be passed by a workflow edit that unmarks a measured state and marks it again, nor by a merge that brings the seed in beside an issue in a measured state without its stage record"
}
```

**What must be true.** An issue that ends a range in a state the workflow marks measured carries its stage record,
however the range got it there.

- Two commits pass today: the first unmarks `measured` on a state in `issues/workflow.yml` and moves an issue there
  without a record; the second marks the state measured again and leaves the issue untouched, so its file is not
  among the changed paths the rule reads.
- A merge whose first parent has no seed and whose second parent brings `stats/seed.csv` keeps an issue of the first
  parent's line in a measured state without its record, since only the paths changed against the first parent are
  read.
- An issue in a measured state from before a repository's seed must still pass; a fix tells the two apart.
