# ST152 — Use the published transport contract

**Priority:** 🔽 Low · **State:** Estimated

**What a user gains.** A developer can build a transport against only the published API and wire libraries and use a stub to pass messages between tasks.

**The test.** Build the stub against only the API and wire libraries and transfer a message between tasks. Offer an unknown protocol version; it is refused by name before use.

**Issues:**

- [porelo#B123](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b123) (Estimated)

## Notes

- Publish The Transport Contract; Provide A Stub Transport. This parent also covers sprints 5 and 6.

## Data

```json
{
  "id": "ST152",
  "title": "Use the published transport contract",
  "state": "Estimated",
  "gain": "A developer can build a transport against only the published API and wire libraries and use a stub to pass messages between tasks.",
  "test": "Build the stub against only the API and wire libraries and transfer a message between tasks. Offer an unknown protocol version; it is refused by name before use.",
  "priority": "Low",
  "issues": [
    "porelo#B123"
  ],
  "tested": null
}
```
