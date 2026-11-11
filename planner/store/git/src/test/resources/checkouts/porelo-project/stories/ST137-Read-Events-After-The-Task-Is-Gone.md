# ST137 — Read events after the task is gone

**Priority:** 🔽 Low · **State:** Analyzed

**What a user gains.** A person can read a task's events after it is gone, even after clearing its runtime directory or restarting the machine.

**The test.** Start, push and remove a task. Clear its runtime directory or restart the machine. Read events using a cursor and older log windows; order and truncation markers are correct. The event journal contains neither credentials nor unrestricted agent output.

**Issues:**

- [porelo#B26](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b26) (Analyzed)
- [porelo#B116](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b116) (Analyzed)

## Data

```json
{
  "id": "ST137",
  "title": "Read events after the task is gone",
  "state": "Analyzed",
  "gain": "A person can read a task's events after it is gone, even after clearing its runtime directory or restarting the machine.",
  "test": "Start, push and remove a task. Clear its runtime directory or restart the machine. Read events using a cursor and older log windows; order and truncation markers are correct. The event journal contains neither credentials nor unrestricted agent output.",
  "priority": "Low",
  "issues": [
    "porelo#B26",
    "porelo#B116"
  ],
  "tested": null
}
```
