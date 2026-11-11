# ST113 — Recover from an unavailable image source

**Priority:** 🟰 Medium · **State:** Idea

**What a user gains.** If the first Ubuntu mirror stops answering, an image build recovers by using the next source instead of failing.

**The test.** Make the first Ubuntu mirror stop answering and build the image. The first attempt ends within its bound and the configured fallback succeeds. An unknown source remains refused.

**Issues:**

- [porelo#B263](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b263) (Idea)

## Data

```json
{
  "id": "ST113",
  "title": "Recover from an unavailable image source",
  "state": "Idea",
  "gain": "If the first Ubuntu mirror stops answering, an image build recovers by using the next source instead of failing.",
  "test": "Make the first Ubuntu mirror stop answering and build the image. The first attempt ends within its bound and the configured fallback succeeds. An unknown source remains refused.",
  "priority": "Medium",
  "issues": [
    "porelo#B263"
  ],
  "tested": null
}
```
