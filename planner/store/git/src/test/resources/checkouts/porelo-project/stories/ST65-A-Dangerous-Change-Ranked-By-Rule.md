# ST65 — A dangerous change ranked by rule

**Priority:** 🟰 Medium · **State:** Idea

**What a user gains.** Waiting work that deletes a test, changes a workflow, adds a dependency or retargets a symlink is ranked as dangerous by rule, so a person looks at it first.

**The test.** Waiting work that deletes a test, changes a workflow, adds a dependency or retargets a symbolic link is ranked C2 or C3 by rule, with the reason shown; a reviewer's finding can raise the class, never lower it.

**Issues:**

- [porelo#B293](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b293) (Idea)
- [porelo#B283](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b283) (Idea)
- [porelo#B141](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b141) (Analyzed)

## Covers

- porelo-project#PJ73

## Data

```json
{
  "id": "ST65",
  "title": "A dangerous change ranked by rule",
  "state": "Idea",
  "gain": "Waiting work that deletes a test, changes a workflow, adds a dependency or retargets a symlink is ranked as dangerous by rule, so a person looks at it first.",
  "test": "Waiting work that deletes a test, changes a workflow, adds a dependency or retargets a symbolic link is ranked C2 or C3 by rule, with the reason shown; a reviewer's finding can raise the class, never lower it.",
  "priority": "Medium",
  "issues": [
    "porelo#B293",
    "porelo#B283",
    "porelo#B141"
  ],
  "tested": null
}
```
