# ST249 — The task hears its build from Forgejo

**Priority:** ⏫ Highest · **State:** Finished (2026-10-27)

**What a user gains.** A task hears its build result from Forgejo after pushing through its gate, so it knows the verdict and reason without a person relaying it.

**The test.** The task pushes through its gate to `porelo/<task>` on Forgejo, which builds it; the verdict and a red job's log are in the task's `/porelo/files`.

**Issues:**

- [porelo-project#PJ61](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj61) (Finished)
- [porelo-adapters#GH08](https://github.com/porelo-ai/porelo-adapters/blob/main/issues/README.md#gh08) (Finished)
- [porelo#B286](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b286) (Finished)
- [porelo-host#HO31](https://github.com/porelo-ai/porelo-host/blob/main/issues/README.md#ho31) (Finished)
- [porelo#B296](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b296) (Finished)
- [porelo-frontend#F194](https://github.com/porelo-ai/porelo-frontend/blob/main/issues/README.md#f194) (Finished)
- [porelo-host#HO33](https://github.com/porelo-ai/porelo-host/blob/main/issues/README.md#ho33) (Finished)

## Data

```json
{
  "id": "ST249",
  "title": "The task hears its build from Forgejo",
  "state": "Finished",
  "gain": "A task hears its build result from Forgejo after pushing through its gate, so it knows the verdict and reason without a person relaying it.",
  "test": "The task pushes through its gate to `porelo/<task>` on Forgejo, which builds it; the verdict and a red job's log are in the task's `/porelo/files`.",
  "priority": "Highest",
  "issues": [
    "porelo-project#PJ61",
    "porelo-adapters#GH08",
    "porelo#B286",
    "porelo-host#HO31",
    "porelo#B296",
    "porelo-frontend#F194",
    "porelo-host#HO33"
  ],
  "tested": "2026-10-27"
}
```
