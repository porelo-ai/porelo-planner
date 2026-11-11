# ST121 — Measure hardware protection first

**Priority:** 🔽 Low · **State:** Estimated

**What a user gains.** A person can measure hardware protection first, with a token present, removed or untouched, before relying on it for unlocking.

**The test.** Measure a token present, removed and untouched. Enroll and unlock with a device-store stand-in. The stored state contains no share; unlock without authorization fails.

**Issues:**

- [porelo#B63](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b63) (Estimated)
- [porelo-frontend#F52](https://github.com/porelo-ai/porelo-frontend/blob/main/issues/README.md#f52) (Estimated)

## Notes

- Measure FIDO2 Share Derivation; Derive Device Shares At Unlock. These parents also cover sprints 6 through 8. Desktop FIDO2 remains a separate later task.

## Data

```json
{
  "id": "ST121",
  "title": "Measure hardware protection first",
  "state": "Estimated",
  "gain": "A person can measure hardware protection first, with a token present, removed or untouched, before relying on it for unlocking.",
  "test": "Measure a token present, removed and untouched. Enroll and unlock with a device-store stand-in. The stored state contains no share; unlock without authorization fails.",
  "priority": "Low",
  "issues": [
    "porelo#B63",
    "porelo-frontend#F52"
  ],
  "tested": null
}
```
