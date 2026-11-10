# PL18 — Moves And Story Work In The Screen Package

```json
{
  "$schema": "issue-1.json",
  "id": "PL18",
  "title": "Moves And Story Work In The Screen Package",
  "state": "Backlog",
  "size": "-",
  "priority": "Medium",
  "rank": 9,
  "blockedBy": ["PL05", "PL08", "PL09"],
  "covers": "The screen package's HTTP calls for saving moves, proposing, reading and stopping story work, an issue's findings and a reading round's decision, so the window can start a story's work"
}
```

**What must be true.** A window that embeds the planning screen can save a story's moves, see the work a working
state proposes, start its tasks with Porelo's own `Start`, and follow and stop that work: each call of the package's
`HttpPlanningSource` that answers "not offered yet" today calls the planner once the planner serves it, with its
refusals answered as `PlanningRefusal`.
