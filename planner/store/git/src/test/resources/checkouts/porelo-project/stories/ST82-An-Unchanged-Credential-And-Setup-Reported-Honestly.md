# ST82 — An unchanged credential and setup reported honestly

**Priority:** 🟰 Medium · **State:** Review

**What a user gains.** Setting the same credential or running setup again reports honestly that nothing changed, in text and in the interface.

**The test.** Set the same credential twice and run setup twice. The second run reports unchanged in text and JSON, with no unnecessary service restart or credential change. Change the value; it is reported as changed.

**Issues:**

- [porelo#B214](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b214) (Review)
- [porelo#B216](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b216) (Review)

## Data

```json
{
  "id": "ST82",
  "title": "An unchanged credential and setup reported honestly",
  "state": "Review",
  "gain": "Setting the same credential or running setup again reports honestly that nothing changed, in text and in the interface.",
  "test": "Set the same credential twice and run setup twice. The second run reports unchanged in text and JSON, with no unnecessary service restart or credential change. Change the value; it is reported as changed.",
  "priority": "Medium",
  "issues": [
    "porelo#B214",
    "porelo#B216"
  ],
  "tested": null
}
```
