# ST128 — Explain a machine quota refusal

**Priority:** 🔽 Low · **State:** Accepted

**What a user gains.** A person is told the actual limit when a machine request is refused because the provider quota is used up.

**The test.** Exhaust the test provider quota and request a leg machine. The refusal states the actual limit returned by the provider. Inspect the inventory afterwards; nothing accidentally created by the refused request remains.

**Issues:**

- [porelo-buildtools#BT09](https://github.com/porelo-ai/porelo-buildtools/blob/main/issues/README.md#bt09) (Accepted)

## Data

```json
{
  "id": "ST128",
  "title": "Explain a machine quota refusal",
  "state": "Accepted",
  "gain": "A person is told the actual limit when a machine request is refused because the provider quota is used up.",
  "test": "Exhaust the test provider quota and request a leg machine. The refusal states the actual limit returned by the provider. Inspect the inventory afterwards; nothing accidentally created by the refused request remains.",
  "priority": "Low",
  "issues": [
    "porelo-buildtools#BT09"
  ],
  "tested": null
}
```
