# PL46 — An Issue Says Whether An Agent Works It

```json
{
  "$schema": "issue-1.json",
  "id": "PL46",
  "title": "An Issue Says Whether An Agent Works It",
  "state": "Idea",
  "size": "M",
  "priority": "Medium",
  "rank": 29,
  "blockedBy": [],
  "covers": "Every issue's head carries who works it, Agent or Manual; the board shows it and lets a person set it, and only an Agent issue gets a task started for it when it reaches a state that starts work"
}
```

**Size:** M - a field in the issue's head and its schema, the board's answer and a move that sets it, and the start obeying it.

**What must be true.** A person decides per issue whether an agent works it: an Agent issue moved to Implementing gets a task with that state's instructions, a Manual issue gets none and the person does the work, and every later step the planner takes by itself for that issue obeys the same choice.

## Plan

- A field `worker` in the issue head, `Agent` or `Manual`, in the issue schema the Git store holds; an issue without it counts as Manual, so no agent is started for an issue nobody chose that for. Creating an issue asks the person to choose Manual or Agent on purpose: the idea form and every other way the planner creates an issue ask, and none fills it in silently.
- The board's `Issue` carries it, a call sets it as a move does, and the screen shows it on the issue's row and lets the person switch it.
- Every start the planner makes for an issue - today a story's work, later the starts by state - leaves out a Manual issue and says so.
- Red first: a story's work over one Agent and one Manual issue starts a task for the Agent issue alone.

## Acceptance

- A Manual issue gets no task in any start, an Agent issue does; the field is read back from a fresh clone after the person sets it.
