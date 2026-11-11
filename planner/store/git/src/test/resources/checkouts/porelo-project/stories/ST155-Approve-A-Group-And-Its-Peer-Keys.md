# ST155 — Approve a group and its peer keys

**Priority:** 🔽 Low · **State:** Backlog

**What a user gains.** A person can create a task group, confirm its peers' fingerprints and message the group, and unconfirmed peers are refused.

**The test.** Create a task group, confirm its fingerprints and send a group message. Offer an unconfirmed peer or changed key; delivery is refused until confirmation.

**Issues:**

- [porelo#B14](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b14) (Backlog)

## Notes

- Address Task Groups; Confirm Peer Fingerprints. This parent also covers sprint 8.

## Data

```json
{
  "id": "ST155",
  "title": "Approve a group and its peer keys",
  "state": "Backlog",
  "gain": "A person can create a task group, confirm its peers' fingerprints and message the group, and unconfirmed peers are refused.",
  "test": "Create a task group, confirm its fingerprints and send a group message. Offer an unconfirmed peer or changed key; delivery is refused until confirmation.",
  "priority": "Low",
  "issues": [
    "porelo#B14"
  ],
  "tested": null
}
```
