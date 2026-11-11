# ST67 — A reviewer reads and comments, and can do nothing more

**Priority:** 🟰 Medium · **State:** Analyzed

**What a user gains.** A person can start a reviewer that reads waiting work and writes findings as comments, and it can do nothing more.

**The test.** `porelo task start --role reviewer` on waiting work writes its findings as comments beside the person's, marked with its task and role; its push, approve and reject are refused by the host.

**Issues:**

- [porelo#B172](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b172) (Analyzed)

## Data

```json
{
  "id": "ST67",
  "title": "A reviewer reads and comments, and can do nothing more",
  "state": "Analyzed",
  "gain": "A person can start a reviewer that reads waiting work and writes findings as comments, and it can do nothing more.",
  "test": "`porelo task start --role reviewer` on waiting work writes its findings as comments beside the person's, marked with its task and role; its push, approve and reject are refused by the host.",
  "priority": "Medium",
  "issues": [
    "porelo#B172"
  ],
  "tested": null
}
```
