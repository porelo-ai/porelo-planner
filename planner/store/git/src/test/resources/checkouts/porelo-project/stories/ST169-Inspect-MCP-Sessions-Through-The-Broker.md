# ST169 — Inspect MCP sessions through the broker

**Priority:** 🔽 Low · **State:** Estimated

**What a user gains.** A person can inspect MCP sessions through the broker, reading tool lists and calls in the journal.

**The test.** Use an HTTP MCP stand-in through the gateway and read its tool list and calls in the journal. Search the task for the server credential; it is absent. A local host command under mcp: is refused.

**Issues:**

- [porelo#B167](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b167) (Estimated)
- [porelo-project#PJ15](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj15) (Estimated)

## Notes

- Measure MCP Sessions Through The Broker. The tool parent also covers sprints 2 through 4; organization rules and audit records must work first.

## Data

```json
{
  "id": "ST169",
  "title": "Inspect MCP sessions through the broker",
  "state": "Estimated",
  "gain": "A person can inspect MCP sessions through the broker, reading tool lists and calls in the journal.",
  "test": "Use an HTTP MCP stand-in through the gateway and read its tool list and calls in the journal. Search the task for the server credential; it is absent. A local host command under mcp: is refused.",
  "priority": "Low",
  "issues": [
    "porelo#B167",
    "porelo-project#PJ15"
  ],
  "tested": null
}
```
