# ST53 — A start that waits for its helpers

**Priority:** 🔼 High · **State:** Idea

**What a user gains.** A start waits until both the gate and the relay are up, and ends with a clear code if either does not come up.

**The test.** A start whose gate or relay does not come up ends with a code; a start waits for both; two starts of one name never stop each other's task.

**Issues:**

- [porelo#B384](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b384) (Idea)

## Data

```json
{
  "id": "ST53",
  "title": "A start that waits for its helpers",
  "state": "Idea",
  "gain": "A start waits until both the gate and the relay are up, and ends with a clear code if either does not come up.",
  "test": "A start whose gate or relay does not come up ends with a code; a start waits for both; two starts of one name never stop each other's task.",
  "priority": "High",
  "issues": [
    "porelo#B384"
  ],
  "tested": null
}
```
