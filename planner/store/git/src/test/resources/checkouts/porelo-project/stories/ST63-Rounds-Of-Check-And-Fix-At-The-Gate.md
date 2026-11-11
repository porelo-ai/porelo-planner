# ST63 — Rounds of check and fix at the gate

**Priority:** 🟰 Medium · **State:** Idea

**What a user gains.** A build task pushes to its gate, a reviewer leaves findings on that commit, and the builder fixes them in rounds before the person is asked.

**The test.** A build task pushes to its gate; a reviewer reads that commit and leaves findings; the build task wakes, fixes and pushes again; the next check is clean, or the rounds stop at their bound.

**Issues:**

- [porelo-project#PJ67](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj67) (Idea)

## Data

```json
{
  "id": "ST63",
  "title": "Rounds of check and fix at the gate",
  "state": "Idea",
  "gain": "A build task pushes to its gate, a reviewer leaves findings on that commit, and the builder fixes them in rounds before the person is asked.",
  "test": "A build task pushes to its gate; a reviewer reads that commit and leaves findings; the build task wakes, fixes and pushes again; the next check is clean, or the rounds stop at their bound.",
  "priority": "Medium",
  "issues": [
    "porelo-project#PJ67"
  ],
  "tested": null
}
```
