# ST115 — Keep project intake and sign-in free of hidden secrets

**Priority:** 🔽 Low · **State:** Idea

**What a user gains.** Project intake and sign-in keep secrets out of sight, so invalid credential names are refused and known test credentials do not leak.

**The test.** Load an invalid credential name; it is explicitly refused. Put a known test credential in the repository; start warns or stops under the project rule. Sign in and inspect container layers and temporary host directories; no plaintext remains there. Store a credential without expiry; the response and list say so.

**Issues:**

- [porelo#B202](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b202) (Accepted)
- [porelo#B140](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b140) (Estimated)
- [porelo#B178](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b178) (Analyzed)
- [porelo#B252](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b252) (Idea)

## Notes

- Decide how existing credential names migrate before enforcing the new names.

## Data

```json
{
  "id": "ST115",
  "title": "Keep project intake and sign-in free of hidden secrets",
  "state": "Idea",
  "gain": "Project intake and sign-in keep secrets out of sight, so invalid credential names are refused and known test credentials do not leak.",
  "test": "Load an invalid credential name; it is explicitly refused. Put a known test credential in the repository; start warns or stops under the project rule. Sign in and inspect container layers and temporary host directories; no plaintext remains there. Store a credential without expiry; the response and list say so.",
  "priority": "Low",
  "issues": [
    "porelo#B202",
    "porelo#B140",
    "porelo#B178",
    "porelo#B252"
  ],
  "tested": null
}
```
