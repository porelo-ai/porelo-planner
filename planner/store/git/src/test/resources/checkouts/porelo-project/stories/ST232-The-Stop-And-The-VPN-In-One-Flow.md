# ST232 — The stop and the VPN in one flow

**Priority:** ⏫ Highest · **State:** Finished (2026-10-25)

**What a user gains.** A person handles the stop and the VPN in one flow, with a small dialog for the Tresorit e-mail and a terminal for the rest.

**The test.** At the stop a small dialog asks for the Tresorit e-mail and opens the terminal; a mistyped password is asked again in place; the VPN comes up as the setup's last step with only the sudo password, its handshake checked, and only then the server is ready.

**Issues:**

- [porelo-frontend#F171](https://github.com/porelo-ai/porelo-frontend/blob/main/issues/README.md#f171) (Finished)
- [porelo-host#HO25](https://github.com/porelo-ai/porelo-host/blob/main/issues/README.md#ho25) (Finished)
- [porelo-frontend#F172](https://github.com/porelo-ai/porelo-frontend/blob/main/issues/README.md#f172) (Finished)

## Data

```json
{
  "id": "ST232",
  "title": "The stop and the VPN in one flow",
  "state": "Finished",
  "gain": "A person handles the stop and the VPN in one flow, with a small dialog for the Tresorit e-mail and a terminal for the rest.",
  "test": "At the stop a small dialog asks for the Tresorit e-mail and opens the terminal; a mistyped password is asked again in place; the VPN comes up as the setup's last step with only the sudo password, its handshake checked, and only then the server is ready.",
  "priority": "Highest",
  "issues": [
    "porelo-frontend#F171",
    "porelo-host#HO25",
    "porelo-frontend#F172"
  ],
  "tested": "2026-10-25"
}
```
