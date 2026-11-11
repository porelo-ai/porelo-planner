# ST117 — Store a forge sign-in without pasting a token

**Priority:** 🔽 Low · **State:** Accepted

**What a user gains.** A person can store a forge sign-in through the device-code login in a terminal, with no token to paste.

**The test.** Complete the forge's device-code login in a terminal on the machine. The token is stored under the forge host's credential name and a task can use it through the host. Cancel the flow; no partial credential is saved.

**Issues:**

- [porelo#B270](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b270) (Accepted)

## Data

```json
{
  "id": "ST117",
  "title": "Store a forge sign-in without pasting a token",
  "state": "Accepted",
  "gain": "A person can store a forge sign-in through the device-code login in a terminal, with no token to paste.",
  "test": "Complete the forge's device-code login in a terminal on the machine. The token is stored under the forge host's credential name and a task can use it through the host. Cancel the flow; no partial credential is saved.",
  "priority": "Low",
  "issues": [
    "porelo#B270"
  ],
  "tested": null
}
```
