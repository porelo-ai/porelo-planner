# ST96 — Open a task port locally

**Priority:** 🟰 Medium · **State:** Analyzed

**What a user gains.** A person can open a task's web server port locally from the host, while access from elsewhere stays closed.

**The test.** Start a web server in the task and open its port. Host-loopback access works; access from the network or through another account's relay socket is refused.

**Issues:**

- [porelo#B165](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b165) (Analyzed)

## Data

```json
{
  "id": "ST96",
  "title": "Open a task port locally",
  "state": "Analyzed",
  "gain": "A person can open a task's web server port locally from the host, while access from elsewhere stays closed.",
  "test": "Start a web server in the task and open its port. Host-loopback access works; access from the network or through another account's relay socket is refused.",
  "priority": "Medium",
  "issues": [
    "porelo#B165"
  ],
  "tested": null
}
```
