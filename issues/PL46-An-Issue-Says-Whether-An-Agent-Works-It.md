# PL46 — An Issue Says Whether An Agent Works It

```json
{
  "$schema": "issue-1.json",
  "id": "PL46",
  "title": "An Issue Says Whether An Agent Works It",
  "state": "Backlog",
  "size": "L",
  "priority": "Medium",
  "rank": 29,
  "blockedBy": [],
  "covers": "Every issue's head carries who works it, Agent or Manual; the board shows it and lets a person set it, and only an Agent issue gets a task started for it when it reaches a state that starts work"
}
```

**Size:** L - a field through every layer (the store's head and save, the work rules' starts in `core`, a new call in
the contract with the window, and the screen package's issue row), each with its tests.

**What must be true.** A person decides per issue whether an agent works it: an Agent issue moved to Implementing gets a task with that state's instructions, a Manual issue gets none and the person does the work, and every later step the planner takes by itself for that issue obeys the same choice.

## Plan

- Creating an issue asks the person to choose Manual or Agent on purpose: the idea form and every other way the
  planner creates an issue ask, and none fills it in silently.
- `store/api`: `Issue` gets `worker`, `AGENT` or `MANUAL`; `Writes` gets the workers a save sets, by issue id.
- `store/git`: `StoreReader` reads `worker` from the issue's head, a head without it as `MANUAL`, an unknown value as a
  fault that leaves the issue out as any broken head does; the issue schema the store holds gains the optional field.
  `GitStore.save` writes a changed `worker` into the head and leaves the index alone, in the same commit and push as
  a move.
- `core`: `WorkProposal`'s issues of a story, and every start `NextStep` plans by itself, leave out a Manual issue
  and name it among the proposal's `manual` issues, so the window can say so; a story whose open issues are all
  Manual proposes no task, and its move goes ahead without a start.
- The contract: `Issue.worker`, the proposal's `manual` list, and `PUT /projects/{project}/issues/{issue}/worker`
  with `{worker, revision}`, saved and answered as a move is, refused at a stale revision.
- The screen package: the issue's row shows Agent or Manual and switches it through the new call.
- Every issue of a project counts as Manual until a person sets it, so the first story's work after this change
  starts nothing until its issues are set to Agent; the changelog says so.
- Tests: red first in `core`, a story's work over one Agent and one Manual issue proposes a task for the Agent issue
  alone and names the Manual one; in `store/git`, a worker set and saved over local bare repositories is read back
  from a fresh clone, a head without it reads Manual, an unknown value is a fault; in `app`, the call's refusals; in
  `screen`, the switch sends the call.

## Acceptance

- A Manual issue gets no task in any start, an Agent issue does; the field is read back from a fresh clone after the person sets it.

## To be checked

- With Frontend: that the window moves a story whose issues are all Manual without opening Start, and shows the
  proposal's Manual issues in the Start dialog.
