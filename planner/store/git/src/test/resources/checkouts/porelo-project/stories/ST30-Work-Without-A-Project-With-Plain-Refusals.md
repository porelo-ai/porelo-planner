# ST30 — Work without a project, with plain refusals

**Priority:** 🔼 High · **State:** Accepted

**What a user gains.** A person can start work without a project and get plain refusals, with a checkout going into the default area unless a followed project claims the repository.

**The test.** A task starts in a checkout in `default`; a followed project naming the repository is offered to take it; a repository with no commit, an unreadable upstream or a failed fetch is named as what it is, with the next step.

**Issues:**

- [porelo-project#PJ14](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj14) (Implementing)
- [porelo#B33](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b33) (Analyzed)
- [porelo-frontend#F118](https://github.com/porelo-ai/porelo-frontend/blob/main/issues/README.md#f118) (Review)
- [porelo#B355](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b355) (Accepted)

## Data

```json
{
  "id": "ST30",
  "title": "Work without a project, with plain refusals",
  "state": "Accepted",
  "gain": "A person can start work without a project and get plain refusals, with a checkout going into the default area unless a followed project claims the repository.",
  "test": "A task starts in a checkout in `default`; a followed project naming the repository is offered to take it; a repository with no commit, an unreadable upstream or a failed fetch is named as what it is, with the next step.",
  "priority": "High",
  "issues": [
    "porelo-project#PJ14",
    "porelo#B33",
    "porelo-frontend#F118",
    "porelo#B355"
  ],
  "tested": null
}
```
