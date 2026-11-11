# ST72 — A forge's calls in a client that knows nothing of Porelo

**Priority:** 🟰 Medium · **State:** Analyzed

**What a user gains.** A client that knows nothing of Porelo can use GitHub's and Forgejo's pull requests, reviews, comments, checks and webhooks.

**The test.** GitHub's and Forgejo's pull requests, reviews and their comments, checks and webhooks are called through `porelo-forge-clients`, one module per forge, under the LGPL; none of its modules names Porelo, its contracts or SARIF.

**Issues:**

- [porelo-project#PJ74](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj74) (Backlog)
- [porelo-forge-clients#FC03](https://github.com/porelo-ai/porelo-forge-clients/blob/main/issues/README.md#fc03) (Analyzed)
- [porelo-forge-clients#FC09](https://github.com/porelo-ai/porelo-forge-clients/blob/main/issues/README.md#fc09) (Finished)
- [porelo-forge-clients#FC10](https://github.com/porelo-ai/porelo-forge-clients/blob/main/issues/README.md#fc10) (Finished)
- [porelo-forge-clients#FC11](https://github.com/porelo-ai/porelo-forge-clients/blob/main/issues/README.md#fc11) (Finished)
- [porelo-forge-clients#FC13](https://github.com/porelo-ai/porelo-forge-clients/blob/main/issues/README.md#fc13) (Finished)

## Data

```json
{
  "id": "ST72",
  "title": "A forge's calls in a client that knows nothing of Porelo",
  "state": "Analyzed",
  "gain": "A client that knows nothing of Porelo can use GitHub's and Forgejo's pull requests, reviews, comments, checks and webhooks.",
  "test": "GitHub's and Forgejo's pull requests, reviews and their comments, checks and webhooks are called through `porelo-forge-clients`, one module per forge, under the LGPL; none of its modules names Porelo, its contracts or SARIF.",
  "priority": "Medium",
  "issues": [
      "porelo-project#PJ74",
      "porelo-forge-clients#FC03",
      "porelo-forge-clients#FC09",
      "porelo-forge-clients#FC10",
      "porelo-forge-clients#FC11",
      "porelo-forge-clients#FC13"
  ],
  "tested": null
}
```
