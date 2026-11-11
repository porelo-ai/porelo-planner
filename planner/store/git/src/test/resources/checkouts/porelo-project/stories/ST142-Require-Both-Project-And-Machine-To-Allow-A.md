# ST142 — Require both project and machine to allow a provider

**Priority:** 🔽 Low · **State:** Analyzed

**What a user gains.** A provider runs only if both the project and the machine's root-owned rule allow it, so a project cannot widen what the machine permits.

**The test.** Configure different provider lists in the project and a root-owned machine rule. Start a provider allowed by both. Try a provider allowed at only one level and a custom provider definition; both are refused with a reason.

**Issues:**

- [porelo#B151](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b151) (Analyzed)
- [porelo-project#PJ16](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj16) (Analyzed)

## Data

```json
{
  "id": "ST142",
  "title": "Require both project and machine to allow a provider",
  "state": "Analyzed",
  "gain": "A provider runs only if both the project and the machine's root-owned rule allow it, so a project cannot widen what the machine permits.",
  "test": "Configure different provider lists in the project and a root-owned machine rule. Start a provider allowed by both. Try a provider allowed at only one level and a custom provider definition; both are refused with a reason.",
  "priority": "Low",
  "issues": [
    "porelo#B151",
    "porelo-project#PJ16"
  ],
  "tested": null
}
```
