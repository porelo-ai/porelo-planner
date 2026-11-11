# ST248 — A refused host shown as refused

**Priority:** ⏫ Highest · **State:** Finished (2026-10-27)

**What a user gains.** A person sees a host the agent refuses named as refused when trying a start with --dry-run, instead of discovering it later.

**The test.** Run task start with --dry-run for an allowed host and one the agent refuses; the latter is named as refused and is refused again by the running task.

**Issues:**

- [porelo#B235](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b235) (Finished)

## Data

```json
{
  "id": "ST248",
  "title": "A refused host shown as refused",
  "state": "Finished",
  "gain": "A person sees a host the agent refuses named as refused when trying a start with --dry-run, instead of discovering it later.",
  "test": "Run task start with --dry-run for an allowed host and one the agent refuses; the latter is named as refused and is refused again by the running task.",
  "priority": "Highest",
  "issues": [
    "porelo#B235"
  ],
  "tested": "2026-10-27"
}
```
