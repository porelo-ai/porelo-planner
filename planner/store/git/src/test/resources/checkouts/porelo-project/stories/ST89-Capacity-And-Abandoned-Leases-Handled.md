# ST89 — Capacity and abandoned leases handled

**Priority:** 🟰 Medium · **State:** Idea

**What a user gains.** When a provider is full the next configured source is used, and machines left by an interrupted run are recovered instead of staying lost.

**The test.** Fill the chosen provider and confirm the configured next source is used. Interrupt a run and lose its state. The sweep finds attributable leftovers, removes only those and reports anything it cannot resolve; other resources remain.

**Issues:** none yet

## Notes

- Respect Provider Capacity; Recover Abandoned Machine Leases. Continue sprint 7 after the state and sweep design is settled.

## Data

```json
{
  "id": "ST89",
  "title": "Capacity and abandoned leases handled",
  "state": "Idea",
  "gain": "When a provider is full the next configured source is used, and machines left by an interrupted run are recovered instead of staying lost.",
  "test": "Fill the chosen provider and confirm the configured next source is used. Interrupt a run and lose its state. The sweep finds attributable leftovers, removes only those and reports anything it cannot resolve; other resources remain.",
  "priority": "Medium",
  "issues": [],
  "tested": null
}
```
