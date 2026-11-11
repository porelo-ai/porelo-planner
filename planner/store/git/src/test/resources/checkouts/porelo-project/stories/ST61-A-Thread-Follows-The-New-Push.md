# ST61 — A thread follows the new push

**Priority:** 🟰 Medium · **State:** Analyzed

**What a user gains.** A review thread follows lines that stay unchanged after a new push, and threads on changed lines stay marked rather than moving wrongly.

**The test.** A thread on lines the new push left unchanged moves with them; one on changed lines stays on its old commit and says so.

**Issues:**

- [porelo#B176](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b176) (Analyzed)

## Data

```json
{
  "id": "ST61",
  "title": "A thread follows the new push",
  "state": "Analyzed",
  "gain": "A review thread follows lines that stay unchanged after a new push, and threads on changed lines stay marked rather than moving wrongly.",
  "test": "A thread on lines the new push left unchanged moves with them; one on changed lines stays on its old commit and says so.",
  "priority": "Medium",
  "issues": [
    "porelo#B176"
  ],
  "tested": null
}
```
