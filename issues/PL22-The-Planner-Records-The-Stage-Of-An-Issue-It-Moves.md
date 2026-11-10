# PL22 — The Planner Records The Stage Of An Issue It Moves

```json
{
  "$schema": "issue-1.json",
  "id": "PL22",
  "title": "The Planner Records The Stage Of An Issue It Moves",
  "state": "Backlog",
  "size": "-",
  "priority": "Medium",
  "rank": 12,
  "blockedBy": ["PL05"],
  "covers": "The planner writes the stage line of an issue it moves, in the record's own format, where a repository records issue stats, instead of refusing the move"
}
```

**What must be true.** A person can move an issue on the board in a repository that records issue stats
(`stats/seed.csv` in its tree), even when the issue has no record yet: the planner's move commit writes the issue's
stage line in `stats/<ID>.jsonl`, in the record's own format, so the commit passes the repository's `check-commits`.
Today the planner refuses such a move before it writes anything.

## Acceptance

- A move of an issue without a record, in a repository with `stats/seed.csv`, is saved, and `porelo-release
  check-commits` passes on the planner's commit; a record the planner writes is read back by the stats tooling as a
  valid stage line.
