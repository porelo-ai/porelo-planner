# ST12 — The release and pipeline tools the move rests on

**Priority:** ⏫ Highest · **State:** Implementing

**What a user gains.** The release and pipeline tools that the move to the host depends on each do their job, such as reading main, handling pull requests and running pipelines.

**The test.** Not a walk: each issue's own acceptance holds: `main` read by its pushes, a pull request from the same repository counted as inside, one directory per release plan, work allowed to wait on work begun, Codex's forge pull requests and snapshot package, and the busy check waiting on a tool the scenario ends.

**Issues:**

- [porelo-buildtools#BT35](https://github.com/porelo-ai/porelo-buildtools/blob/main/issues/README.md#bt35) (Finished)
- [porelo-buildtools#BT36](https://github.com/porelo-ai/porelo-buildtools/blob/main/issues/README.md#bt36) (Review)
- [porelo-buildtools#BT38](https://github.com/porelo-ai/porelo-buildtools/blob/main/issues/README.md#bt38) (Review)
- [porelo-buildtools#BT40](https://github.com/porelo-ai/porelo-buildtools/blob/main/issues/README.md#bt40) (Review)
- [porelo-codex#CX03](https://github.com/porelo-ai/porelo-codex/blob/main/issues/README.md#cx03) (Review)
- [porelo-codex#CX05](https://github.com/porelo-ai/porelo-codex/blob/main/issues/README.md#cx05) (Review)
- [porelo-omp#OM39](https://github.com/porelo-ai/porelo-omp/blob/main/issues/README.md#om39) (Review)

## Data

```json
{
  "id": "ST12",
  "title": "The release and pipeline tools the move rests on",
  "state": "Implementing",
  "gain": "The release and pipeline tools that the move to the host depends on each do their job, such as reading main, handling pull requests and running pipelines.",
  "test": "Not a walk: each issue's own acceptance holds: `main` read by its pushes, a pull request from the same repository counted as inside, one directory per release plan, work allowed to wait on work begun, Codex's forge pull requests and snapshot package, and the busy check waiting on a tool the scenario ends.",
  "priority": "Highest",
  "issues": [
    "porelo-buildtools#BT35",
    "porelo-buildtools#BT36",
    "porelo-buildtools#BT38",
    "porelo-buildtools#BT40",
    "porelo-codex#CX03",
    "porelo-codex#CX05",
    "porelo-omp#OM39"
  ],
  "tested": null
}
```
