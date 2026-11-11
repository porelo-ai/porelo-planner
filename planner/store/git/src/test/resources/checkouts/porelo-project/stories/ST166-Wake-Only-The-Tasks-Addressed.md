# ST166 — Wake only the tasks addressed

**Priority:** 🔽 Low · **State:** Accepted

**What a user gains.** Only the tasks addressed by nickname or @room are woken, including with non-ASCII text, and a message mentioning no one wakes none.

**The test.** Set two unique nicknames and send non-ASCII text mentioning one, then @room, then neither. First only the named task wakes, then all; the unaddressed message wakes none and is read at the next wake. Duplicate nicknames are refused and the interface shows the same names.

**Issues:**

- [porelo#B112](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b112) (Estimated)
- [porelo-frontend#F88](https://github.com/porelo-ai/porelo-frontend/blob/main/issues/README.md#f88) (Estimated)
- [porelo#B274](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b274) (Accepted)

## Data

```json
{
  "id": "ST166",
  "title": "Wake only the tasks addressed",
  "state": "Accepted",
  "gain": "Only the tasks addressed by nickname or @room are woken, including with non-ASCII text, and a message mentioning no one wakes none.",
  "test": "Set two unique nicknames and send non-ASCII text mentioning one, then @room, then neither. First only the named task wakes, then all; the unaddressed message wakes none and is read at the next wake. Duplicate nicknames are refused and the interface shows the same names.",
  "priority": "Low",
  "issues": [
    "porelo#B112",
    "porelo-frontend#F88",
    "porelo#B274"
  ],
  "tested": null
}
```
