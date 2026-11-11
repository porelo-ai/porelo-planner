# ST85 — Resume setup and read a failed job

**Priority:** 🟰 Medium · **State:** Idea

**What a user gains.** A person who closes the interface or SSH during setup can reconnect and find the running setup, and can read a failed job in full.

**The test.** Close the interface or SSH during setup, reconnect and find the running setup with its full log. A second start is refused. Fail an unattended CI job; the red run, ::error and summary name the cause. No issue is opened automatically.

**Issues:**

- [porelo-host#HO24](https://github.com/porelo-ai/porelo-host/blob/main/issues/README.md#ho24) (Idea)
- [porelo-project#PJ08](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj08) (Estimated)

## Data

```json
{
  "id": "ST85",
  "title": "Resume setup and read a failed job",
  "state": "Idea",
  "gain": "A person who closes the interface or SSH during setup can reconnect and find the running setup, and can read a failed job in full.",
  "test": "Close the interface or SSH during setup, reconnect and find the running setup with its full log. A second start is refused. Fail an unattended CI job; the red run, ::error and summary name the cause. No issue is opened automatically.",
  "priority": "Medium",
  "issues": [
    "porelo-host#HO24",
    "porelo-project#PJ08"
  ],
  "tested": null
}
```
