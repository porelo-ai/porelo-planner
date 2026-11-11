# ST189 — Install the Mac application

**Priority:** 🔽 Low · **State:** Estimated

**What a user gains.** A person on a Mac can install the application in a fresh account and verify its checksum or attestation.

**The test.** Install in a fresh account, verify the checksum or attestation and follow the documented Gatekeeper step. Open a terminal and type. A build without even an ad hoc signature is not accepted as an installable result.

**Issues:**

- [porelo-frontend#F109](https://github.com/porelo-ai/porelo-frontend/blob/main/issues/README.md#f109) (Estimated)

## Notes

- Build The macOS Frontend; Publish The macOS Application Package. This parent also covers sprint 6.

## Data

```json
{
  "id": "ST189",
  "title": "Install the Mac application",
  "state": "Estimated",
  "gain": "A person on a Mac can install the application in a fresh account and verify its checksum or attestation.",
  "test": "Install in a fresh account, verify the checksum or attestation and follow the documented Gatekeeper step. Open a terminal and type. A build without even an ad hoc signature is not accepted as an installable result.",
  "priority": "Low",
  "issues": [
    "porelo-frontend#F109"
  ],
  "tested": null
}
```
