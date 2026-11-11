# ST193 — Choose drive areas for each host

**Priority:** 🔽 Low · **State:** Implementing

**What a user gains.** A person can choose the drive areas for each host, so each host receives only its own whether vaults are separate or shared.

**The test.** Configure two hosts in separate vaults and then in a shared vault. Each receives only its chosen area; an unselected personal area never appears.

**Issues:**

- [porelo-project#PJ106](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj106) (Backlog)

## Notes

- Describe Personal And Host Drives; Mount Only Selected Host Drive Areas. The access boundaries from release 0.16 must work first.

## Data

```json
{
  "id": "ST193",
  "title": "Choose drive areas for each host",
  "state": "Implementing",
  "gain": "A person can choose the drive areas for each host, so each host receives only its own whether vaults are separate or shared.",
  "test": "Configure two hosts in separate vaults and then in a shared vault. Each receives only its chosen area; an unselected personal area never appears.",
  "priority": "Low",
  "issues": [
      "porelo-project#PJ106"
  ],
  "tested": null
}
```
