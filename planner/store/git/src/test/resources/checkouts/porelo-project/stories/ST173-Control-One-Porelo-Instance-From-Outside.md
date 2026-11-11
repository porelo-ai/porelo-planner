# ST173 — Control one Porelo instance from outside

**Priority:** 🔽 Low · **State:** Accepted

**What a user gains.** A person can control one Porelo instance from outside over MCP to start tasks, read status and send messages, while approving, rejecting and vault access stay refused.

**The test.** Start a task, read status and send a message over MCP. Then try approve, reject and vault unlock; those operations are unavailable or explicitly refused.

**Issues:**

- [porelo-project#PJ35](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj35) (Accepted)

## Notes

- Control One Porelo Instance Over MCP; Refuse Approval And Vault Operations. This parent also covers sprint 6.

## Data

```json
{
  "id": "ST173",
  "title": "Control one Porelo instance from outside",
  "state": "Accepted",
  "gain": "A person can control one Porelo instance from outside over MCP to start tasks, read status and send messages, while approving, rejecting and vault access stay refused.",
  "test": "Start a task, read status and send a message over MCP. Then try approve, reject and vault unlock; those operations are unavailable or explicitly refused.",
  "priority": "Low",
  "issues": [
    "porelo-project#PJ35"
  ],
  "tested": null
}
```
