# ST138 — Detect changes to the history

**Priority:** 🔽 Low · **State:** Analyzed

**What a user gains.** A person can detect changes to the history, since a changed journal line or a truncated end is noticed on verification.

**The test.** Approve, reject and change a credential. Change a journal line and truncate the end; verification names both changes. Run clear; the evidence remains. Remove the old message hash chain only after this proof.

**Issues:**

- [porelo#B154](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b154) (Implementing)
- [porelo#B121](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b121) (Analyzed)

## Notes

- Use the shared event journal, without a second journal implementation.

## Data

```json
{
  "id": "ST138",
  "title": "Detect changes to the history",
  "state": "Analyzed",
  "gain": "A person can detect changes to the history, since a changed journal line or a truncated end is noticed on verification.",
  "test": "Approve, reject and change a credential. Change a journal line and truncate the end; verification names both changes. Run clear; the evidence remains. Remove the old message hash chain only after this proof.",
  "priority": "Low",
  "issues": [
    "porelo#B154",
    "porelo#B121"
  ],
  "tested": null
}
```
