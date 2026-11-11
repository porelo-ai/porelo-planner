# ST90 — Review once and preserve unmerged work

**Priority:** 🟰 Medium · **State:** Idea

**What a user gains.** The same green build starts only one reviewer, and unmerged work is preserved rather than lost.

**The test.** Report the same green build more than once; exactly one reviewer starts for that commit. Merge a task pull request; a task with no remaining work is removed, while unmerged work stays visible. With no project opt-in, neither action runs.

**Issues:**

- [porelo#B288](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b288) (Idea)
- [porelo#B289](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b289) (Idea)

## Data

```json
{
  "id": "ST90",
  "title": "Review once and preserve unmerged work",
  "state": "Idea",
  "gain": "The same green build starts only one reviewer, and unmerged work is preserved rather than lost.",
  "test": "Report the same green build more than once; exactly one reviewer starts for that commit. Merge a task pull request; a task with no remaining work is removed, while unmerged work stays visible. With no project opt-in, neither action runs.",
  "priority": "Medium",
  "issues": [
    "porelo#B288",
    "porelo#B289"
  ],
  "tested": null
}
```
