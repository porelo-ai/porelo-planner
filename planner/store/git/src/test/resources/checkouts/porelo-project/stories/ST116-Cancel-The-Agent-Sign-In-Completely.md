# ST116 — Cancel the agent sign-in completely

**Priority:** 🔽 Low · **State:** Analyzed

**What a user gains.** A person can cancel an agent's sign-in completely with the supported escape, even if the agent ignores it.

**The test.** Start vault login in its own terminal and cancel using the supported escape even if the agent consumes Ctrl-C. The login container stops and is removed, nothing is stored and the command exits nonzero.

**Issues:**

- [porelo#B271](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b271) (Analyzed)

## Data

```json
{
  "id": "ST116",
  "title": "Cancel the agent sign-in completely",
  "state": "Analyzed",
  "gain": "A person can cancel an agent's sign-in completely with the supported escape, even if the agent ignores it.",
  "test": "Start vault login in its own terminal and cancel using the supported escape even if the agent consumes Ctrl-C. The login container stops and is removed, nothing is stored and the command exits nonzero.",
  "priority": "Low",
  "issues": [
    "porelo#B271"
  ],
  "tested": null
}
```
