# ST51 — A KeePassXC key that is usable

**Priority:** 🔼 High · **State:** Implementing

**What a user gains.** A person using KeePassXC is asked for the master password once when making a key, and a retry works after the key is reported as not served.

**The test.** A new key made in KeePassXC asks for the master password once; a retry after key-not-served takes the key already made; no empty or orphaned entry is left; without a terminal the refusal says so.

**Issues:**

- [porelo#B385](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b385) (Implementing)

## Data

```json
{
  "id": "ST51",
  "title": "A KeePassXC key that is usable",
  "state": "Implementing",
  "gain": "A person using KeePassXC is asked for the master password once when making a key, and a retry works after the key is reported as not served.",
  "test": "A new key made in KeePassXC asks for the master password once; a retry after key-not-served takes the key already made; no empty or orphaned entry is left; without a terminal the refusal says so.",
  "priority": "High",
  "issues": [
    "porelo#B385"
  ],
  "tested": null
}
```
