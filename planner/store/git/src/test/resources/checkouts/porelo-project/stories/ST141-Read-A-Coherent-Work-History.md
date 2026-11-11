# ST141 — Read a coherent work history

**Priority:** 🔽 Low · **State:** Estimated

**What a user gains.** A person reads a coherent work history, replaying a session with terminal, broker and gate events and filtering the timeline.

**The test.** Create a session with terminal, broker and gate events. Replay it and filter the timeline by source; time order is retained. Reach size and retention limits; neither host nor interface blocks.

**Issues:**

- [porelo#B168](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b168) (Estimated)
- [porelo-frontend#F111](https://github.com/porelo-ai/porelo-frontend/blob/main/issues/README.md#f111) (Backlog)

## Data

```json
{
  "id": "ST141",
  "title": "Read a coherent work history",
  "state": "Estimated",
  "gain": "A person reads a coherent work history, replaying a session with terminal, broker and gate events and filtering the timeline.",
  "test": "Create a session with terminal, broker and gate events. Replay it and filter the timeline by source; time order is retained. Reach size and retention limits; neither host nor interface blocks.",
  "priority": "Low",
  "issues": [
    "porelo#B168",
    "porelo-frontend#F111"
  ],
  "tested": null
}
```
