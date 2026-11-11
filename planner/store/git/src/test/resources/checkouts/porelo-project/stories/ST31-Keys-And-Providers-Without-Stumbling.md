# ST31 — Keys and providers without stumbling

**Priority:** 🔼 High · **State:** Analyzed

**What a user gains.** A person can paste a key into vault put and have it read whole, and an agent given an unknown provider is refused with a clear message.

**The test.** A key pasted at `vault put` is read whole; an agent given a provider it does not know is refused before the start.

**Issues:**

- [porelo#B228](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b228) (Analyzed)
- [porelo#P09](https://github.com/porelo-ai/porelo/blob/main/issues/providers/README.md#p09) (Estimated)
- [porelo#B316](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b316) (Estimated)

## Data

```json
{
  "id": "ST31",
  "title": "Keys and providers without stumbling",
  "state": "Analyzed",
  "gain": "A person can paste a key into vault put and have it read whole, and an agent given an unknown provider is refused with a clear message.",
  "test": "A key pasted at `vault put` is read whole; an agent given a provider it does not know is refused before the start.",
  "priority": "High",
  "issues": [
    "porelo#B228",
    "porelo#P09",
    "porelo#B316"
  ],
  "tested": null
}
```
