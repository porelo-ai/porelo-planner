# ST94 — Approve one of several attempts

**Priority:** 🟰 Medium · **State:** Analyzed

**What a user gains.** A person can start several attempts from one commit, compare their differences and approve one, while the others are dropped.

**The test.** Start several attempts from one commit, read their differences and approve one. The other attempts are rejected; any rate limit is shown for the affected attempt.

**Issues:**

- [porelo#B163](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b163) (Analyzed)

## Data

```json
{
  "id": "ST94",
  "title": "Approve one of several attempts",
  "state": "Analyzed",
  "gain": "A person can start several attempts from one commit, compare their differences and approve one, while the others are dropped.",
  "test": "Start several attempts from one commit, read their differences and approve one. The other attempts are rejected; any rate limit is shown for the affected attempt.",
  "priority": "Medium",
  "issues": [
    "porelo#B163"
  ],
  "tested": null
}
```
