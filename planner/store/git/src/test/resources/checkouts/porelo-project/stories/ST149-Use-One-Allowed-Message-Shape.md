# ST149 — Use one allowed message shape

**Priority:** 🔽 Low · **State:** Analyzed

**What a user gains.** Messages use one allowed shape, so extra metadata keys, a second text part and similar variations are refused.

**The test.** Send every permitted message kind, then send an extra metadata key, a second text part and a forbidden data part. Valid messages arrive; each invalid message is refused with its offending element named.

**Issues:**

- [porelo#B108](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b108) (Analyzed)
- [porelo-message-sluice#SL23](https://github.com/porelo-ai/porelo-message-sluice/blob/main/issues/README.md#sl23) (Estimated)

## Data

```json
{
  "id": "ST149",
  "title": "Use one allowed message shape",
  "state": "Analyzed",
  "gain": "Messages use one allowed shape, so extra metadata keys, a second text part and similar variations are refused.",
  "test": "Send every permitted message kind, then send an extra metadata key, a second text part and a forbidden data part. Valid messages arrive; each invalid message is refused with its offending element named.",
  "priority": "Low",
  "issues": [
    "porelo#B108",
    "porelo-message-sluice#SL23"
  ],
  "tested": null
}
```
