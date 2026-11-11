# ST148 — Recognize a token awaiting organization approval

**Priority:** 🔽 Low · **State:** Analyzed

**What a user gains.** A person creating a project is told clearly when the GitHub token still awaits organization approval.

**The test.** Create a project against the GitHub test organization with a token awaiting approval. The interface says it is waiting for approval instead of calling it invalid; after approval the operation succeeds.

**Issues:**

- [porelo-frontend#F115](https://github.com/porelo-ai/porelo-frontend/blob/main/issues/README.md#f115) (Analyzed)
- [porelo-frontend#F59](https://github.com/porelo-ai/porelo-frontend/blob/main/issues/README.md#f59) (Estimated)

## Data

```json
{
  "id": "ST148",
  "title": "Recognize a token awaiting organization approval",
  "state": "Analyzed",
  "gain": "A person creating a project is told clearly when the GitHub token still awaits organization approval.",
  "test": "Create a project against the GitHub test organization with a token awaiting approval. The interface says it is waiting for approval instead of calling it invalid; after approval the operation succeeds.",
  "priority": "Low",
  "issues": [
    "porelo-frontend#F115",
    "porelo-frontend#F59"
  ],
  "tested": null
}
```
