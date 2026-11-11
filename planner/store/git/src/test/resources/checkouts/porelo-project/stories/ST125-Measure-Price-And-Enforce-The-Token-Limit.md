# ST125 — Measure price and enforce the token limit

**Priority:** 🔽 Low · **State:** Analyzed

**What a user gains.** A person sees what each model costs by dated price, an unpriced request is not shown as free, and the token limit is enforced.

**The test.** Use two models with dated prices and read an unpriced request; it is not shown as free. Reach a small token limit; further provider calls are refused. Raise the limit and deliberately resume.

**Issues:**

- [porelo#B191](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b191) (Analyzed)
- [porelo#B138](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b138) (Estimated)

## Notes

- The usage record from release 0.6 is a prerequisite.

## Data

```json
{
  "id": "ST125",
  "title": "Measure price and enforce the token limit",
  "state": "Analyzed",
  "gain": "A person sees what each model costs by dated price, an unpriced request is not shown as free, and the token limit is enforced.",
  "test": "Use two models with dated prices and read an unpriced request; it is not shown as free. Reach a small token limit; further provider calls are refused. Raise the limit and deliberately resume.",
  "priority": "Low",
  "issues": [
    "porelo#B191",
    "porelo#B138"
  ],
  "tested": null
}
```
