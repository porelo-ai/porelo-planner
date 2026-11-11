# ST111 — Install from the intended package source

**Priority:** 🟰 Medium · **State:** Accepted

**What a user gains.** A person installing through the interface on fresh Fedora, Debian or Ubuntu gets packages only from the intended source, and a changed setup script is caught.

**The test.** Install through the interface on fresh Fedora and Debian or Ubuntu. Change the setup script; it must not run. Offer an agent package with the same name from another source; it is not selected. Install the correctly signed package and confirm package checking remains enabled.

**Issues:**

- [porelo#B196](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b196) (Estimated)
- [porelo-frontend#F81](https://github.com/porelo-ai/porelo-frontend/blob/main/issues/README.md#f81) (Estimated)
- [porelo#B260](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b260) (Accepted)

## Data

```json
{
  "id": "ST111",
  "title": "Install from the intended package source",
  "state": "Accepted",
  "gain": "A person installing through the interface on fresh Fedora, Debian or Ubuntu gets packages only from the intended source, and a changed setup script is caught.",
  "test": "Install through the interface on fresh Fedora and Debian or Ubuntu. Change the setup script; it must not run. Offer an agent package with the same name from another source; it is not selected. Install the correctly signed package and confirm package checking remains enabled.",
  "priority": "Medium",
  "issues": [
    "porelo#B196",
    "porelo-frontend#F81",
    "porelo#B260"
  ],
  "tested": null
}
```
