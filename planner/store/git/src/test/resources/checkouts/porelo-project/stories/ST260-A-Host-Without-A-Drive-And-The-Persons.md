# ST260 — A host without a drive, and the person's own drive in their account

**Priority:** ⏫ Highest · **State:** Finished (2026-10-28)

**What a user gains.** A person can rent a host without a drive, typing only the master password and sudo, or use their own drive in their own account.

**The test.** Rent without a drive: no Tresorit client, only the master password and sudo typed. Rent again with the person's drive: it is mounted in the person's account, and Porelo's account cannot list it.

**Issues:**

- [porelo-host#HO38](https://github.com/porelo-ai/porelo-host/blob/main/issues/README.md#ho38) (Finished)
- [porelo-frontend#F201](https://github.com/porelo-ai/porelo-frontend/blob/main/issues/README.md#f201) (Finished)

## Covers

- porelo-project#PJ50

## Data

```json
{
  "id": "ST260",
  "title": "A host without a drive, and the person's own drive in their account",
  "state": "Finished",
  "gain": "A person can rent a host without a drive, typing only the master password and sudo, or use their own drive in their own account.",
  "test": "Rent without a drive: no Tresorit client, only the master password and sudo typed. Rent again with the person's drive: it is mounted in the person's account, and Porelo's account cannot list it.",
  "priority": "Highest",
  "issues": [
    "porelo-host#HO38",
    "porelo-frontend#F201"
  ],
  "tested": "2026-10-28"
}
```
