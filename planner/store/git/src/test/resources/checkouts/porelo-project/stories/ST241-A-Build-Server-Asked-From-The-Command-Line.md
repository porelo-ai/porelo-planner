# ST241 — A build server asked from the command line

**Priority:** ⏫ Highest · **State:** Finished (2026-10-25)

**What a user gains.** A person can ask a build server from the command line with porelo build status and see the commit and its result for a branch.

**The test.** `porelo build status --project=<project> --repository=<repository> --branch=main` shows the commit, the verdict, the jobs and a red job's log, on GitHub.

**Issues:**

- [porelo#B256](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b256) (Finished)

## Data

```json
{
  "id": "ST241",
  "title": "A build server asked from the command line",
  "state": "Finished",
  "gain": "A person can ask a build server from the command line with porelo build status and see the commit and its result for a branch.",
  "test": "`porelo build status --project=<project> --repository=<repository> --branch=main` shows the commit, the verdict, the jobs and a red job's log, on GitHub.",
  "priority": "Highest",
  "issues": [
    "porelo#B256"
  ],
  "tested": "2026-10-25"
}
```
