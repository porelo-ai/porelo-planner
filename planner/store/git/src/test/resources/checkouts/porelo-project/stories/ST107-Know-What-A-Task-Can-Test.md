# ST107 — Know what a task can test

**Priority:** 🟰 Medium · **State:** Accepted

**What a user gains.** A person knows what a task can test, since capability-tagged tests run only with the needed capability and skip only what lacks it.

**The test.** Run capability-tagged tests with and without the needed capability. A task skips only what it lacks; a complete CI leg runs every case. Emit a declared failure in task.log after the step baseline; the loop watch stops the run and names it.

**Issues:**

- [porelo-project#PJ49](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj49) (Accepted)
- [porelo#B119](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b119) (Analyzed)

## Notes

- Capability tags never replace a complete green build on the forge.

## Data

```json
{
  "id": "ST107",
  "title": "Know what a task can test",
  "state": "Accepted",
  "gain": "A person knows what a task can test, since capability-tagged tests run only with the needed capability and skip only what lacks it.",
  "test": "Run capability-tagged tests with and without the needed capability. A task skips only what it lacks; a complete CI leg runs every case. Emit a declared failure in task.log after the step baseline; the loop watch stops the run and names it.",
  "priority": "Medium",
  "issues": [
    "porelo-project#PJ49",
    "porelo#B119"
  ],
  "tested": null
}
```
