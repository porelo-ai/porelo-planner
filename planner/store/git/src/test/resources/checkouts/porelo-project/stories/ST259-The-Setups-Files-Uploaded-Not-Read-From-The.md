# ST259 — The setup's files uploaded, not read from the drive

**Priority:** ⏫ Highest · **State:** Finished (2026-10-28)

**What a user gains.** A person can rent a host from a drive that holds neither the machine file nor the bundle, since the setup's files are uploaded rather than read from the drive.

**The test.** Rent once with a drive holding neither `machine.yml` nor the bundle; the setup comes up from `/etc/porelo-host/setup/`, a digest mismatch refuses `copy`, and a `machine.yml` the account can write is refused.

**Issues:**

- [porelo-host#HO36](https://github.com/porelo-ai/porelo-host/blob/main/issues/README.md#ho36) (Finished)
- [porelo-frontend#F197](https://github.com/porelo-ai/porelo-frontend/blob/main/issues/README.md#f197) (Finished)

## Covers

- porelo-project#PJ50

## Data

```json
{
  "id": "ST259",
  "title": "The setup's files uploaded, not read from the drive",
  "state": "Finished",
  "gain": "A person can rent a host from a drive that holds neither the machine file nor the bundle, since the setup's files are uploaded rather than read from the drive.",
  "test": "Rent once with a drive holding neither `machine.yml` nor the bundle; the setup comes up from `/etc/porelo-host/setup/`, a digest mismatch refuses `copy`, and a `machine.yml` the account can write is refused.",
  "priority": "Highest",
  "issues": [
    "porelo-host#HO36",
    "porelo-frontend#F197"
  ],
  "tested": "2026-10-28"
}
```
