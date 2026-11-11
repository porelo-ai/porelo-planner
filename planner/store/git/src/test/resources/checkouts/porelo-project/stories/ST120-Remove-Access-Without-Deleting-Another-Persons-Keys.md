# ST120 — Remove access without deleting another person's keys

**Priority:** 🔽 Low · **State:** Estimated

**What a user gains.** A person can unfollow a project or clear a machine and lose access without deleting another person's keys.

**The test.** Follow and unfollow a project, then clear the machine. Read remaining deploy keys, unreviewed or unpushed work and old unused slots. Remove the project's keys in the GitHub test account; another project's key remains.

**Issues:**

- [porelo#B117](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b117) (Estimated)
- [porelo#B153](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b153) (Estimated)

## Data

```json
{
  "id": "ST120",
  "title": "Remove access without deleting another person's keys",
  "state": "Estimated",
  "gain": "A person can unfollow a project or clear a machine and lose access without deleting another person's keys.",
  "test": "Follow and unfollow a project, then clear the machine. Read remaining deploy keys, unreviewed or unpushed work and old unused slots. Remove the project's keys in the GitHub test account; another project's key remains.",
  "priority": "Low",
  "issues": [
    "porelo#B117",
    "porelo#B153"
  ],
  "tested": null
}
```
