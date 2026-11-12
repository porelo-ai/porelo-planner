# PL83 — The Work Numbers Per Phase Kept And Served

```json
{
  "$schema": "issue-1.json",
  "id": "PL83",
  "title": "The Work Numbers Per Phase Kept And Served",
  "state": "Idea",
  "size": "-",
  "priority": "High",
  "rank": 1,
  "blockedBy": [],
  "covers": "A task is started with the phase it works in, the Git store keeps a finished issue's numbers from Porelo's records in stats/, and the velocity and an issue's tokens per phase reach the window and the stage command from those records"
}
```

**What must be true.** The minutes and tokens an issue took in each phase come from Porelo's record of its tasks,
kept with the issue where the store can keep them, and the estimates agents are given and the window shows read
them instead of what agents wrote about themselves.

- `PlanningStartTask` carries the phase a task works in, and the window hands the issue and phase to Porelo's
  `Start`, which takes one `{issue, phase}` per task; a MAIN task working several issues names them otherwise or
  not at all. A change to the window's contract, agreed with the window first.
- The Git store keeps a finished issue's numbers in `stats/<ID>.jsonl` from the records, so they outlive the computer
  that ran the tasks, and the velocity reads the kept numbers where the records are gone, counting nothing twice.
- The stage command's `median` and `p80` answer from the records once the build tools no longer carry `stage`.
- The velocity is served to the window.
- An issue's tokens and its exact time per sub-agent, once Porelo's records name a sub-agent's span and issue,
  count tokens per sub-agent, and take an id with a hyphen.
