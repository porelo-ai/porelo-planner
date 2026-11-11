# ST66 — A C3 change approved by two people, on what they read

**Priority:** 🟰 Medium · **State:** Analyzed

**What a user gains.** A risky C3 change needs a second person to approve it, and a push after review voids the approval so people approve what they read.

**The test.** Approving a C3 change asks for a second person; a push the agent makes between review and approval is never forwarded unseen.

**Issues:**

- [porelo#B145](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b145) (Analyzed)

## Data

```json
{
  "id": "ST66",
  "title": "A C3 change approved by two people, on what they read",
  "state": "Analyzed",
  "gain": "A risky C3 change needs a second person to approve it, and a push after review voids the approval so people approve what they read.",
  "test": "Approving a C3 change asks for a second person; a push the agent makes between review and approval is never forwarded unseen.",
  "priority": "Medium",
  "issues": [
    "porelo#B145"
  ],
  "tested": null
}
```
