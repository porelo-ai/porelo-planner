# ST83 — An expiring token told ahead

**Priority:** 🟰 Medium · **State:** Accepted

**What a user gains.** A person is told ahead of time, once, when a forge token on a host is about to expire.

**The test.** A forge token set to expire soon on a host makes one message to the person, in the way they chose, naming the machine, the token, when it expires and how to renew it; it is not repeated until it changes.

**Issues:**

- [porelo-project#PJ70](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj70) (Accepted)

## Data

```json
{
  "id": "ST83",
  "title": "An expiring token told ahead",
  "state": "Accepted",
  "gain": "A person is told ahead of time, once, when a forge token on a host is about to expire.",
  "test": "A forge token set to expire soon on a host makes one message to the person, in the way they chose, naming the machine, the token, when it expires and how to renew it; it is not repeated until it changes.",
  "priority": "Medium",
  "issues": [
    "porelo-project#PJ70"
  ],
  "tested": null
}
```
