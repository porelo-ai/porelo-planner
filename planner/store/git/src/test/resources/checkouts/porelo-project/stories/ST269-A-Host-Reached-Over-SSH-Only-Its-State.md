# ST269 — A host reached over SSH only, its state taken from the registry

**Priority:** ⏫ Highest · **State:** Finished (2026-11-02)

**What a user gains.** A host is reached over SSH only, and takes its project admins, projects and persons from the signed registry state, refusing mismatched or revoked cards with the reason.

**The test.** The host's only open port is SSH, the forge and homeserver are reached through their gates over the tunnel, and the host takes its admins, projects and persons from the signed registry state, refusing a card under another login, an unsigned host card or a successor signed by a revoked key, with reasons.

**Issues:**

- [porelo-host#HO68](https://github.com/porelo-ai/porelo-host/blob/main/issues/README.md#ho68) (Finished)
- [porelo-host#HO69](https://github.com/porelo-ai/porelo-host/blob/main/issues/README.md#ho69) (Finished)
- [porelo-host#HO80](https://github.com/porelo-ai/porelo-host/blob/main/issues/README.md#ho80) (Finished)
- [porelo-host#HO70](https://github.com/porelo-ai/porelo-host/blob/main/issues/README.md#ho70) (Finished)
- [porelo-host#HO75](https://github.com/porelo-ai/porelo-host/blob/main/issues/README.md#ho75) (Finished)
- [porelo-host#HO89](https://github.com/porelo-ai/porelo-host/blob/main/issues/README.md#ho89) (Finished)
- [porelo-message-matrix#MX62](https://github.com/porelo-ai/porelo-message-matrix/blob/main/issues/README.md#mx62) (Finished)
- [porelo-message-matrix#MX61](https://github.com/porelo-ai/porelo-message-matrix/blob/main/issues/README.md#mx61) (Finished)
- [porelo-host#HO73](https://github.com/porelo-ai/porelo-host/blob/main/issues/README.md#ho73) (Finished)
- [porelo#B349](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b349) (Finished)
- [porelo#B351](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b351) (Finished)
- [porelo#B340](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b340) (Finished)
- [porelo#B342](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b342) (Finished)
- [porelo-frontend#F270](https://github.com/porelo-ai/porelo-frontend/blob/main/issues/README.md#f270) (Finished)
- [porelo-intellij#IJ18](https://github.com/porelo-ai/porelo-intellij/blob/main/issues/README.md#ij18) (Finished)

## Covers

- porelo-project#PJ83

## Data

```json
{
  "id": "ST269",
  "title": "A host reached over SSH only, its state taken from the registry",
  "state": "Finished",
  "gain": "A host is reached over SSH only, and takes its project admins, projects and persons from the signed registry state, refusing mismatched or revoked cards with the reason.",
  "test": "The host's only open port is SSH, the forge and homeserver are reached through their gates over the tunnel, and the host takes its admins, projects and persons from the signed registry state, refusing a card under another login, an unsigned host card or a successor signed by a revoked key, with reasons.",
  "priority": "Highest",
  "issues": [
    "porelo-host#HO68",
    "porelo-host#HO69",
    "porelo-host#HO80",
    "porelo-host#HO70",
    "porelo-host#HO75",
    "porelo-host#HO89",
    "porelo-message-matrix#MX62",
    "porelo-message-matrix#MX61",
    "porelo-host#HO73",
    "porelo#B349",
    "porelo#B351",
    "porelo#B340",
    "porelo#B342",
    "porelo-frontend#F270",
    "porelo-intellij#IJ18"
  ],
  "tested": "2026-11-02"
}
```
