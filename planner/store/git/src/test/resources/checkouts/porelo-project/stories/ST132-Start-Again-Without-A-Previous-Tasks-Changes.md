# ST132 — Start again without a previous task's changes

**Priority:** 🔽 Low · **State:** Idea

**What a user gains.** A new task starts clean, inheriting none of an earlier task's Pi settings, extensions or PATH changes.

**The test.** Change Pi settings, extensions and PATH in a task; a new task inherits none of them. Make a continuation fail without a session identifier. The recorded session is discarded and the next start says it is fresh.

**Issues:**

- [porelo-pi#PI37](https://github.com/porelo-ai/porelo-pi/blob/main/issues/README.md#pi37) (Idea)
- [porelo#B282](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b282) (Idea)

## Data

```json
{
  "id": "ST132",
  "title": "Start again without a previous task's changes",
  "state": "Idea",
  "gain": "A new task starts clean, inheriting none of an earlier task's Pi settings, extensions or PATH changes.",
  "test": "Change Pi settings, extensions and PATH in a task; a new task inherits none of them. Make a continuation fail without a session identifier. The recorded session is discarded and the next start says it is fresh.",
  "priority": "Low",
  "issues": [
    "porelo-pi#PI37",
    "porelo#B282"
  ],
  "tested": null
}
```
