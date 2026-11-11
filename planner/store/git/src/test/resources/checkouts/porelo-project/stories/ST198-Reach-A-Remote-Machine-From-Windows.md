# ST198 — Reach a remote machine from Windows

**Priority:** 🔽 Low · **State:** Estimated

**What a user gains.** A person on Windows with no SSH binary can still connect to a host, start a task and reconnect.

**The test.** On Windows without an SSH binary, connect to the host, start a task and reconnect. A wrong host key is refused and the relay carries the full stream.

**Issues:**

- [porelo-frontend#F37](https://github.com/porelo-ai/porelo-frontend/blob/main/issues/README.md#f37) (Estimated)
- [porelo-frontend#F108](https://github.com/porelo-ai/porelo-frontend/blob/main/issues/README.md#f108) (Review)

## Data

```json
{
  "id": "ST198",
  "title": "Reach a remote machine from Windows",
  "state": "Estimated",
  "gain": "A person on Windows with no SSH binary can still connect to a host, start a task and reconnect.",
  "test": "On Windows without an SSH binary, connect to the host, start a task and reconnect. A wrong host key is refused and the relay carries the full stream.",
  "priority": "Low",
  "issues": [
    "porelo-frontend#F37",
    "porelo-frontend#F108"
  ],
  "tested": null
}
```
