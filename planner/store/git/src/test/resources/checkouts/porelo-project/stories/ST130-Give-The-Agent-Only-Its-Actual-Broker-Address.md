# ST130 — Give the agent only its actual broker address

**Priority:** 🔽 Low · **State:** Idea

**What a user gains.** An agent gets only its actual broker address, and a task without a broker gets none.

**The test.** Compare brokered and brokerless starts. Only a task with a broker receives its broker address; an agent reached by URL must not treat a relay as a broker.

**Issues:**

- [porelo#B250](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b250) (Idea)

## Data

```json
{
  "id": "ST130",
  "title": "Give the agent only its actual broker address",
  "state": "Idea",
  "gain": "An agent gets only its actual broker address, and a task without a broker gets none.",
  "test": "Compare brokered and brokerless starts. Only a task with a broker receives its broker address; an agent reached by URL must not treat a relay as a broker.",
  "priority": "Low",
  "issues": [
    "porelo#B250"
  ],
  "tested": null
}
```
