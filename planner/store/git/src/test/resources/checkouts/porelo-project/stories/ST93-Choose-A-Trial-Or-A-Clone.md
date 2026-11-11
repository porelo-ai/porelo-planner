# ST93 — Choose a trial or a clone

**Priority:** 🟰 Medium · **State:** Analyzed

**What a user gains.** A person can choose from the interface to run a waiting commit in a trial container or to fetch it as a clone.

**The test.** Run a waiting commit in a trial container from the interface, then fetch the same commit into a chosen clone. Commit and result are visible. Choose a clone holding other or unsaved work; that work must not be overwritten.

**Issues:**

- [porelo-frontend#F74](https://github.com/porelo-ai/porelo-frontend/blob/main/issues/README.md#f74) (Analyzed)
- [porelo-frontend#F95](https://github.com/porelo-ai/porelo-frontend/blob/main/issues/README.md#f95) (Analyzed)

## Notes

- Confirm how local, WSL and SSH targets are addressed before building this step.

## Data

```json
{
  "id": "ST93",
  "title": "Choose a trial or a clone",
  "state": "Analyzed",
  "gain": "A person can choose from the interface to run a waiting commit in a trial container or to fetch it as a clone.",
  "test": "Run a waiting commit in a trial container from the interface, then fetch the same commit into a chosen clone. Commit and result are visible. Choose a clone holding other or unsaved work; that work must not be overwritten.",
  "priority": "Medium",
  "issues": [
    "porelo-frontend#F74",
    "porelo-frontend#F95"
  ],
  "tested": null
}
```
