# PL65 — Work Obeys An Issue's Worker

```json
{
  "$schema": "issue-1.json",
  "id": "PL65",
  "title": "Work Obeys An Issue's Worker",
  "state": "Idea",
  "size": "L",
  "priority": "Medium",
  "rank": 1,
  "blockedBy": ["PL46"],
  "covers": "Only an Agent issue gets a task: the proposal and every step the planner starts by itself leave a Manual issue out, a person moves a Manual issue through the work, and a story of both kinds follows its least advanced issue"
}
```

**What must be true.** An issue a person works gets no agent's task, in any start or any step the planner takes by
itself, and the person can still move it through the work; an issue an agent works goes on as today.

- The work proposal and every step the planner starts by itself leave a Manual issue out and name it, so the window
  can say so; a story whose open issues are all Manual proposes no task.
- A story with Agent and Manual issues: its Agent issues get their tasks, its Manual issues are moved by the person,
  and the story's state follows its least advanced issue. A sub-issue takes its parent's worker.
- A person may move a Manual issue, and only a Manual one, by hand into a working state and on from there
  (Implementing, Review and further), with no task and no dialog; an Agent issue still enters a working state only by
  its work.
- The screen offers the switch to Manual and the choice of Manual for a new issue, and counts no Manual issue as
  work without a run.
