# ST91 — A merge task and event route used deliberately

**Priority:** 🟰 Medium · **State:** Idea

**What a user gains.** A merge task starts only when it matches the advanced main, and a person can route events deliberately.

**The test.** Advance central main and refuse the gathered push; a matching merge task starts only with project opt-in. Send an event for account A; only its daemon receives it. Unknown or forged senders are refused. With the router absent, polling still works.

**Issues:**

- [porelo#B290](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b290) (Idea)
- [porelo-host#HO28](https://github.com/porelo-ai/porelo-host/blob/main/issues/README.md#ho28) (Idea)

## Data

```json
{
  "id": "ST91",
  "title": "A merge task and event route used deliberately",
  "state": "Idea",
  "gain": "A merge task starts only when it matches the advanced main, and a person can route events deliberately.",
  "test": "Advance central main and refuse the gathered push; a matching merge task starts only with project opt-in. Send an event for account A; only its daemon receives it. Unknown or forged senders are refused. With the router absent, polling still works.",
  "priority": "Medium",
  "issues": [
    "porelo#B290",
    "porelo-host#HO28"
  ],
  "tested": null
}
```
