# ST261 — The forge opened in the browser once the VPN is up

**Priority:** ⏫ Highest · **State:** Finished (2026-10-28)

**What a user gains.** A person can open the forge in the browser once the VPN is up and sign in with the lasting password.

**The test.** After the VPN, open `https://forge.porelo.internal/` and sign in with the lasting password: no hosts line, CA import or password file by hand; the CA's key never reached the host. A second host needs no new CA in Firefox; a certificate for another name signed by the CA fails `openssl verify`.

**Issues:**

- [porelo-host#HO35](https://github.com/porelo-ai/porelo-host/blob/main/issues/README.md#ho35) (Finished)
- [porelo-frontend#F202](https://github.com/porelo-ai/porelo-frontend/blob/main/issues/README.md#f202) (Finished)

## Data

```json
{
  "id": "ST261",
  "title": "The forge opened in the browser once the VPN is up",
  "state": "Finished",
  "gain": "A person can open the forge in the browser once the VPN is up and sign in with the lasting password.",
  "test": "After the VPN, open `https://forge.porelo.internal/` and sign in with the lasting password: no hosts line, CA import or password file by hand; the CA's key never reached the host. A second host needs no new CA in Firefox; a certificate for another name signed by the CA fails `openssl verify`.",
  "priority": "Highest",
  "issues": [
    "porelo-host#HO35",
    "porelo-frontend#F202"
  ],
  "tested": "2026-10-28"
}
```
