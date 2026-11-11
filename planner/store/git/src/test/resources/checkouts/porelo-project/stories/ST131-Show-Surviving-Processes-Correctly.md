# ST131 — Show surviving processes correctly

**Priority:** 🔽 Low · **State:** Idea

**What a user gains.** A person sees surviving processes of a stopped task shown the same way in the daemon result and the interface.

**The test.** Stop a task with surviving processes and compare the daemon result with the interface. Both show the same actual integer count, including a nonzero case.

**Issues:**

- [porelo#B262](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b262) (Idea)

## Data

```json
{
  "id": "ST131",
  "title": "Show surviving processes correctly",
  "state": "Idea",
  "gain": "A person sees surviving processes of a stopped task shown the same way in the daemon result and the interface.",
  "test": "Stop a task with surviving processes and compare the daemon result with the interface. Both show the same actual integer count, including a nonzero case.",
  "priority": "Low",
  "issues": [
    "porelo#B262"
  ],
  "tested": null
}
```
