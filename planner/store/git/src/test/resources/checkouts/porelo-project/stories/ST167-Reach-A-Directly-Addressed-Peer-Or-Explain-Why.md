# ST167 — Reach a directly addressed peer or explain why not

**Priority:** 🔽 Low · **State:** Idea

**What a user gains.** A message to a directly addressed peer arrives, or the sender is told plainly why it cannot.

**The test.** Send to a peer with a valid direct transport address. Remove the required account and start or send again; the operation is refused with the missing requirement named rather than silently losing the message.

**Issues:**

- [porelo#B257](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b257) (Idea)

## Data

```json
{
  "id": "ST167",
  "title": "Reach a directly addressed peer or explain why not",
  "state": "Idea",
  "gain": "A message to a directly addressed peer arrives, or the sender is told plainly why it cannot.",
  "test": "Send to a peer with a valid direct transport address. Remove the required account and start or send again; the operation is refused with the missing requirement named rather than silently losing the message.",
  "priority": "Low",
  "issues": [
    "porelo#B257"
  ],
  "tested": null
}
```
