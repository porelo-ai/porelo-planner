# ST119 — Give a transport its secret through the intended input

**Priority:** 🔽 Low · **State:** Review

**What a user gains.** A transport such as Matrix gets its secret through the intended input, so it does not show in process environments or arguments.

**The test.** Start Matrix with a test token, deliver a message and inspect /proc environments and process arguments during startup and operation. The token is absent. A transport with an unsupported secret-input protocol is refused before use.

**Issues:**

- [porelo#B206](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b206) (Review)
- [porelo-message-matrix#MX23](https://github.com/porelo-ai/porelo-message-matrix/blob/main/issues/README.md#mx23) (Review)

## Notes

- Change the host contract before adapting Matrix.

## Data

```json
{
  "id": "ST119",
  "title": "Give a transport its secret through the intended input",
  "state": "Review",
  "gain": "A transport such as Matrix gets its secret through the intended input, so it does not show in process environments or arguments.",
  "test": "Start Matrix with a test token, deliver a message and inspect /proc environments and process arguments during startup and operation. The token is absent. A transport with an unsupported secret-input protocol is refused before use.",
  "priority": "Low",
  "issues": [
    "porelo#B206",
    "porelo-message-matrix#MX23"
  ],
  "tested": null
}
```
