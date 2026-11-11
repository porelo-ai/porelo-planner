# ST228 — Secrets only in a terminal, by the provider's own login

**Priority:** ⏫ Highest · **State:** Finished (2026-10-24)

**What a user gains.** A person gives secrets only in a terminal, using the drive provider's own login, so Porelo never handles the password itself.

**The test.** At the host's stop a terminal opens with Tresorit's own login, the code asked after the password; the vault's passphrase is typed there; the forge is reached with the person's token from GitHub's device-code login, and no deploy key is made; Ctrl+Shift+V pastes in every terminal of the interface.

**Issues:**

- [porelo-frontend#F152](https://github.com/porelo-ai/porelo-frontend/blob/main/issues/README.md#f152) (Finished)
- [porelo-frontend#F153](https://github.com/porelo-ai/porelo-frontend/blob/main/issues/README.md#f153) (Finished)
- [porelo-frontend#F160](https://github.com/porelo-ai/porelo-frontend/blob/main/issues/README.md#f160) (Finished)
- [porelo#B269](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b269) (Finished)

## Covers

- porelo-project#PJ69

## Data

```json
{
  "id": "ST228",
  "title": "Secrets only in a terminal, by the provider's own login",
  "state": "Finished",
  "gain": "A person gives secrets only in a terminal, using the drive provider's own login, so Porelo never handles the password itself.",
  "test": "At the host's stop a terminal opens with Tresorit's own login, the code asked after the password; the vault's passphrase is typed there; the forge is reached with the person's token from GitHub's device-code login, and no deploy key is made; Ctrl+Shift+V pastes in every terminal of the interface.",
  "priority": "Highest",
  "issues": [
    "porelo-frontend#F152",
    "porelo-frontend#F153",
    "porelo-frontend#F160",
    "porelo#B269"
  ],
  "tested": "2026-10-24"
}
```
