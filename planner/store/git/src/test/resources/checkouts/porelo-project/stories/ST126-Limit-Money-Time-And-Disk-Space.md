# ST126 — Limit money, time and disk space

**Priority:** 🔽 Low · **State:** Analyzed

**What a user gains.** A person can limit money, runtime and disk space, and an unknown price cannot bypass the dollar limit.

**The test.** Cross a low dollar limit; an unknown price cannot bypass it. Exceed runtime with and without a person attached; the defined deferral rule holds. Exceed disk and mailbox limits and reduce free space below its minimum. Status names each reason and how to continue.

**Issues:**

- [porelo#B177](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b177) (Analyzed)
- [porelo#B147](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b147) (Estimated)
- [porelo#B185](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b185) (Estimated)

## Data

```json
{
  "id": "ST126",
  "title": "Limit money, time and disk space",
  "state": "Analyzed",
  "gain": "A person can limit money, runtime and disk space, and an unknown price cannot bypass the dollar limit.",
  "test": "Cross a low dollar limit; an unknown price cannot bypass it. Exceed runtime with and without a person attached; the defined deferral rule holds. Exceed disk and mailbox limits and reduce free space below its minimum. Status names each reason and how to continue.",
  "priority": "Low",
  "issues": [
    "porelo#B177",
    "porelo#B147",
    "porelo#B185"
  ],
  "tested": null
}
```
