# ST284 — The host vault opened by the person's key

**Priority:** ⏫ Highest · **State:** Finished (2026-11-03)

**What a user gains.** Opening the tunnel unlocks the person's host vault with their key, with no passphrase or prompt.

**The test.** Opening the tunnel unlocks the person's host vault without a passphrase or prompt; the share stays in locked memory until the host restarts or the person is locked; after a reboot the vault waits for the next connection; a lost key means the vault is deleted and set up again.

**Issues:**

- [porelo#B345](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b345) (Finished)

## Covers

- porelo-project#PJ83

## Data

```json
{
  "id": "ST284",
  "title": "The host vault opened by the person's key",
  "state": "Finished",
  "gain": "Opening the tunnel unlocks the person's host vault with their key, with no passphrase or prompt.",
  "test": "Opening the tunnel unlocks the person's host vault without a passphrase or prompt; the share stays in locked memory until the host restarts or the person is locked; after a reboot the vault waits for the next connection; a lost key means the vault is deleted and set up again.",
  "priority": "Highest",
  "issues": [
    "porelo#B345"
  ],
  "tested": "2026-11-03"
}
```
