# ST139 — See origin and current inventory

**Priority:** 🔽 Low · **State:** Analyzed

**What a user gains.** A person sees where a handed-over file came from and what the task changed, even after the file is removed.

**The test.** Hand over a file, let the task modify files and remove it. Origin and change size remain readable. Compare text and JSON inventory: agents, providers, credentials, projects, models and last use agree; secret values are absent.

**Issues:**

- [porelo#B38](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b38) (Analyzed)
- [porelo#B156](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b156) (Estimated)

## Data

```json
{
  "id": "ST139",
  "title": "See origin and current inventory",
  "state": "Analyzed",
  "gain": "A person sees where a handed-over file came from and what the task changed, even after the file is removed.",
  "test": "Hand over a file, let the task modify files and remove it. Origin and change size remain readable. Compare text and JSON inventory: agents, providers, credentials, projects, models and last use agree; secret values are absent.",
  "priority": "Low",
  "issues": [
    "porelo#B38",
    "porelo#B156"
  ],
  "tested": null
}
```
