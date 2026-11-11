# ST157 — Let a limited service manage task accounts

**Priority:** 🔽 Low · **State:** Accepted

**What a user gains.** A limited project service can manage task accounts as its own task but cannot act as a person or another project.

**The test.** Register the project service and act as its own task. Try acting as a person or another project's task; both fail. Retire and restore the service; old tokens no longer work.

**Issues:**

- [porelo-message-matrix#MX38](https://github.com/porelo-ai/porelo-message-matrix/blob/main/issues/README.md#mx38) (Accepted)

## Notes

- Register A Project Application Service; Rotate And Revoke Service Secrets. Continue the room parent from sprint 3 after sprint 8.

## Data

```json
{
  "id": "ST157",
  "title": "Let a limited service manage task accounts",
  "state": "Accepted",
  "gain": "A limited project service can manage task accounts as its own task but cannot act as a person or another project.",
  "test": "Register the project service and act as its own task. Try acting as a person or another project's task; both fail. Retire and restore the service; old tokens no longer work.",
  "priority": "Low",
  "issues": [
    "porelo-message-matrix#MX38"
  ],
  "tested": null
}
```
