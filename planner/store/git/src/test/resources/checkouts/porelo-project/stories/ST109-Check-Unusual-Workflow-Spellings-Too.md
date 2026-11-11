# ST109 — Check unusual workflow spellings too

**Priority:** 🟰 Medium · **State:** Accepted

**What a user gains.** Workflow files are checked even when written in unusual ways, so a corrected parent builds every Maven repository and a plugin version restore is caught.

**The test.** Build every Maven repository with the corrected parent. Restore a plugin version with a known advisory; the check names it. Offer the same forbidden workflow permission normally, quoted and in flow YAML; all forms are refused.

**Issues:**

- [porelo#B210](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b210) (Estimated)
- [porelo-buildtools#BT30](https://github.com/porelo-ai/porelo-buildtools/blob/main/issues/README.md#bt30) (Accepted)

## Data

```json
{
  "id": "ST109",
  "title": "Check unusual workflow spellings too",
  "state": "Accepted",
  "gain": "Workflow files are checked even when written in unusual ways, so a corrected parent builds every Maven repository and a plugin version restore is caught.",
  "test": "Build every Maven repository with the corrected parent. Restore a plugin version with a known advisory; the check names it. Offer the same forbidden workflow permission normally, quoted and in flow YAML; all forms are refused.",
  "priority": "Medium",
  "issues": [
    "porelo#B210",
    "porelo-buildtools#BT30"
  ],
  "tested": null
}
```
