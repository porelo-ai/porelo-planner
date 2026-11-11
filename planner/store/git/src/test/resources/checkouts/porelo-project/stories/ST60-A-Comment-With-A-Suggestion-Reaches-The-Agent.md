# ST60 — A comment with a suggestion reaches the agent

**Priority:** 🟰 Medium · **State:** Analyzed

**What a user gains.** A person can comment on a line with a suggestion in the review, and the task building that work receives it.

**The test.** In the interface's review a person comments on a line with a suggestion; the building task receives it as untrusted review input, pushes a fix, and the review shows the thread answered on the new commit.

**Issues:**

- [porelo#B136](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b136) (Analyzed)
- [porelo#B150](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b150) (Analyzed)
- [porelo#B166](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b166) (Analyzed)
- [porelo-frontend#F78](https://github.com/porelo-ai/porelo-frontend/blob/main/issues/README.md#f78) (Analyzed)
- [porelo-frontend#F107](https://github.com/porelo-ai/porelo-frontend/blob/main/issues/README.md#f107) (Analyzed)

## Data

```json
{
  "id": "ST60",
  "title": "A comment with a suggestion reaches the agent",
  "state": "Analyzed",
  "gain": "A person can comment on a line with a suggestion in the review, and the task building that work receives it.",
  "test": "In the interface's review a person comments on a line with a suggestion; the building task receives it as untrusted review input, pushes a fix, and the review shows the thread answered on the new commit.",
  "priority": "Medium",
  "issues": [
    "porelo#B136",
    "porelo#B150",
    "porelo#B166",
    "porelo-frontend#F78",
    "porelo-frontend#F107"
  ],
  "tested": null
}
```
