# ST100 — Check allowed destinations

**Priority:** 🟰 Medium · **State:** Analyzed

**What a user gains.** A person sees whether the machine can check allowed destinations, and a task reaches only the addresses, domains and sets its project allows.

**The test.** Read resolver capability in doctor. Start a project with addresses, domains and sets. Reach an allowed destination; an unallowed one fails and is shown as refused. Allow a CNAME while the task runs; it becomes reachable. An incomplete set must not appear complete.

**Issues:**

- [porelo#B34](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b34) (Analyzed)
- [porelo#B10](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b10) (Analyzed)
- [porelo#B237](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b237) (Estimated)
- [porelo#B152](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b152) (Analyzed)

## Data

```json
{
  "id": "ST100",
  "title": "Check allowed destinations",
  "state": "Analyzed",
  "gain": "A person sees whether the machine can check allowed destinations, and a task reaches only the addresses, domains and sets its project allows.",
  "test": "Read resolver capability in doctor. Start a project with addresses, domains and sets. Reach an allowed destination; an unallowed one fails and is shown as refused. Allow a CNAME while the task runs; it becomes reachable. An incomplete set must not appear complete.",
  "priority": "Medium",
  "issues": [
    "porelo#B34",
    "porelo#B10",
    "porelo#B237",
    "porelo#B152"
  ],
  "tested": null
}
```
