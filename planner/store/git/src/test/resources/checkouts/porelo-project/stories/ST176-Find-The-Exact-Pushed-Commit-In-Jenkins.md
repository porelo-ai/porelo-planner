# ST176 — Find the exact pushed commit in Jenkins

**Priority:** 🔽 Low · **State:** Accepted

**What a user gains.** A task finds the exact pushed commit in Jenkins and receives its stages, verdict and failing log, for a green and a red commit.

**The test.** Push a green and a red commit. The task receives commit, stages, verdict and the failing log. Offer a build of another commit; it is not accepted as the result of this push. The task contains no Jenkins credential.

**Issues:**

- [porelo-project#PJ36](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj36) (Accepted)

## Notes

- Read Jenkins Builds Independently Of The Forge; Match Bitbucket Pushes To Jenkins Builds. The build contract from release 0.6 must work first.

## Data

```json
{
  "id": "ST176",
  "title": "Find the exact pushed commit in Jenkins",
  "state": "Accepted",
  "gain": "A task finds the exact pushed commit in Jenkins and receives its stages, verdict and failing log, for a green and a red commit.",
  "test": "Push a green and a red commit. The task receives commit, stages, verdict and the failing log. Offer a build of another commit; it is not accepted as the result of this push. The task contains no Jenkins credential.",
  "priority": "Low",
  "issues": [
    "porelo-project#PJ36"
  ],
  "tested": null
}
```
