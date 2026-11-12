# PL90 — A Reader Is Started With The Work It Reads

```json
{
  "$schema": "issue-1.json",
  "id": "PL90",
  "title": "A Reader Is Started With The Work It Reads",
  "state": "Idea",
  "size": "-",
  "priority": "High",
  "rank": 1,
  "blockedBy": [],
  "covers": "A reader's order names the branch each task it reads landed on, as Porelo reports it with that task's end, and the reader is started naming those tasks, so Porelo's gate gives the reader their work to read"
}
```

**What must be true.** A reader offered after a build reads that build's work: its order names the branch the build's
work landed on, as Porelo reports it on the build's end, never a name the planner makes up; and its offer names the
tasks whose work it reads, so the window starts it naming them and Porelo's gate gives the reader those branches, read
only. Today the order names only the build's task, the reader's `origin` carries no other task's branch, and the
reader is offered at the build's end even while its work still waits at the gate.
