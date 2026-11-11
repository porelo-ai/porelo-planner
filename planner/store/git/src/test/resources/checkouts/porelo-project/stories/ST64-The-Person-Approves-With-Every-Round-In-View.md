# ST64 — The person approves with every round in view

**Priority:** 🟰 Medium · **State:** Idea

**What a user gains.** A person approving at the gate or in the interface sees the diff with the latest findings and all the rounds that led to it.

**The test.** `porelo gate review` and the interface show the diff with the last findings and the rounds before it, compared by `baselineState`; a finding the agent marked suppressed still counts until a person clears it.

**Issues:** none yet

## Notes

- findings shown at the gate in porelo and the interface (from PJ67)

## Data

```json
{
  "id": "ST64",
  "title": "The person approves with every round in view",
  "state": "Idea",
  "gain": "A person approving at the gate or in the interface sees the diff with the latest findings and all the rounds that led to it.",
  "test": "`porelo gate review` and the interface show the diff with the last findings and the rounds before it, compared by `baselineState`; a finding the agent marked suppressed still counts until a person clears it.",
  "priority": "Medium",
  "issues": [],
  "tested": null
}
```
