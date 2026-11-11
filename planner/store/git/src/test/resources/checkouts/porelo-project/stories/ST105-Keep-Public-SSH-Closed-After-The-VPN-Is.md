# ST105 — Keep public SSH closed after the VPN is ready

**Priority:** 🟰 Medium · **State:** Idea

**What a user gains.** Once the VPN is ready, public SSH stays closed, and a documented recovery path exists if the VPN breaks.

**The test.** Bring up the VPN, then try public SSH; it fails. Break the VPN and follow the documented recovery route successfully. Do not enable the restriction until this recovery route has been tested.

**Issues:**

- [porelo-host#HO22](https://github.com/porelo-ai/porelo-host/blob/main/issues/README.md#ho22) (Rejected)

## Data

```json
{
  "id": "ST105",
  "title": "Keep public SSH closed after the VPN is ready",
  "state": "Idea",
  "gain": "Once the VPN is ready, public SSH stays closed, and a documented recovery path exists if the VPN breaks.",
  "test": "Bring up the VPN, then try public SSH; it fails. Break the VPN and follow the documented recovery route successfully. Do not enable the restriction until this recovery route has been tested.",
  "priority": "Medium",
  "issues": [
    "porelo-host#HO22"
  ],
  "tested": null
}
```
