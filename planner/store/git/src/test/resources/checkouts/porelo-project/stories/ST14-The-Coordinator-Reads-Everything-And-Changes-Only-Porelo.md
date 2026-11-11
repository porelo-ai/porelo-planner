# ST14 — The coordinator reads everything and changes only porelo-project

**Priority:** ⏫ Highest · **State:** Analyzed

**What a user gains.** The coordinator can read every repository of the project but can change only the project repository, and any change elsewhere is refused.

**The test.** In `porelo-all` every repository is there; a change outside `porelo-project/` is refused.

**Issues:**

- [porelo-project#PJ58](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj58) (Analyzed)

## Data

```json
{
  "id": "ST14",
  "title": "The coordinator reads everything and changes only porelo-project",
  "state": "Analyzed",
  "gain": "The coordinator can read every repository of the project but can change only the project repository, and any change elsewhere is refused.",
  "test": "In `porelo-all` every repository is there; a change outside `porelo-project/` is refused.",
  "priority": "Highest",
  "issues": [
    "porelo-project#PJ58"
  ],
  "tested": null
}
```
