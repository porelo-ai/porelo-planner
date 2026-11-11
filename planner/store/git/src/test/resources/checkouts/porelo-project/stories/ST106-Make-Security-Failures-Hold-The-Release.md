# ST106 — Make security failures hold the release

**Priority:** 🟰 Medium · **State:** Estimated

**What a user gains.** A release is held back when a security check fails, because Fedora isolation and the misuse tests must pass on their intended legs.

**The test.** Run Fedora isolation and all three misuse groups on their intended legs. Disable one protection in each group; its test turns red and blocks release approval. Restore protection; every required case runs, and an inapplicable skip cannot count as success.

**Issues:**

- [porelo#B51](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b51) (Estimated)
- [porelo#B142](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b142) (Estimated)

## Data

```json
{
  "id": "ST106",
  "title": "Make security failures hold the release",
  "state": "Estimated",
  "gain": "A release is held back when a security check fails, because Fedora isolation and the misuse tests must pass on their intended legs.",
  "test": "Run Fedora isolation and all three misuse groups on their intended legs. Disable one protection in each group; its test turns red and blocks release approval. Restore protection; every required case runs, and an inapplicable skip cannot count as success.",
  "priority": "Medium",
  "issues": [
    "porelo#B51",
    "porelo#B142"
  ],
  "tested": null
}
```
