# ST140 — Keep reliable commit details and private logs

**Priority:** 🔽 Low · **State:** Estimated

**What a user gains.** A person reads reliable commit details in merge trailers, forged agent details are rejected, and private logs stay private.

**The test.** Approve work from different models and read the merge trailers. Offer forged agent details; they cannot replace the host record. Send a test secret through broker and gate; it appears in no log. File permissions and retention match the stated rules.

**Issues:**

- [porelo#B144](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b144) (Estimated)
- [porelo#B155](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b155) (Estimated)
- [porelo#B143](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b143) (Estimated)

## Data

```json
{
  "id": "ST140",
  "title": "Keep reliable commit details and private logs",
  "state": "Estimated",
  "gain": "A person reads reliable commit details in merge trailers, forged agent details are rejected, and private logs stay private.",
  "test": "Approve work from different models and read the merge trailers. Offer forged agent details; they cannot replace the host record. Send a test secret through broker and gate; it appears in no log. File permissions and retention match the stated rules.",
  "priority": "Low",
  "issues": [
    "porelo#B144",
    "porelo#B155",
    "porelo#B143"
  ],
  "tested": null
}
```
