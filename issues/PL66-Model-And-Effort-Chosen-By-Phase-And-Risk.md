# PL66 — Model And Effort Chosen By Phase And Risk

```json
{
  "$schema": "issue-1.json",
  "id": "PL66",
  "title": "Model And Effort Chosen By Phase And Risk",
  "state": "Accepted",
  "size": "-",
  "priority": "Medium",
  "rank": 1,
  "blockedBy": [],
  "covers": "An issue's head may say how much harm its change can do, and the work rules give per working state the model and effort for each risk, which a start hands to the window"
}
```

**What must be true.** A task that works an issue whose change can do harm gets the model and effort the project's
work rules set for that risk in that working state, and the person sees where the choice came from; an issue that names
no risk is worked as an ordinary one.

- An issue's head may carry `risk`: `harm`, `ordinary` (when none is set) or `small`. Writing it into a head waits
  until the build tools' issue schema takes it, as the worker field did.
- The work rules give per working state the effort for each risk; a start hands agent, model and effort to the window
  and names where the choice came from. What an effort means for each agent is Porelo's.
- Red first, unit: a Reading task for a `harm` issue carries the rule's effort for `harm`.

## To be checked

- What the window's Start takes today for an effort, and whether Porelo maps one effort scale per agent.
