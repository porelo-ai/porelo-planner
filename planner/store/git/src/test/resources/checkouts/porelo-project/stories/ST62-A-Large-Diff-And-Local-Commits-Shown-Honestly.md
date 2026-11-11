# ST62 — A large diff and local commits shown honestly

**Priority:** 🟰 Medium · **State:** Analyzed

**What a user gains.** A person opening a very large diff sees it cut at a file boundary with a plain notice, and local commits are shown honestly.

**The test.** Open a review larger than 4 MiB; it is cut at a file boundary, says so and keeps the full file list. Read a running task with local commits, then stop it. Summary, commits and diff agree in both states; missing parts must not appear complete.

**Issues:**

- [porelo#B174](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b174) (Estimated)
- [porelo-frontend#F112](https://github.com/porelo-ai/porelo-frontend/blob/main/issues/README.md#f112) (Analyzed)

## Data

```json
{
  "id": "ST62",
  "title": "A large diff and local commits shown honestly",
  "state": "Analyzed",
  "gain": "A person opening a very large diff sees it cut at a file boundary with a plain notice, and local commits are shown honestly.",
  "test": "Open a review larger than 4 MiB; it is cut at a file boundary, says so and keeps the full file list. Read a running task with local commits, then stop it. Summary, commits and diff agree in both states; missing parts must not appear complete.",
  "priority": "Medium",
  "issues": [
    "porelo#B174",
    "porelo-frontend#F112"
  ],
  "tested": null
}
```
