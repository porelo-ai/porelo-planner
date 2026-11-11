# ST258 — The host's secrets from one file, typed as one master password

**Priority:** ⏫ Highest · **State:** Finished (2026-10-28)

**What a user gains.** A person can rent a host with its secrets taken from one file and typed as one master password on the desktop.

**The test.** Rent a host from a drive holding only `machine.yml` and the bundle; on the desktop type the master password once, at the host the drive's login; the host comes up with its VPN key in `/etc/wireguard` and its tokens and vault keys from the file, and no secret lies on the drive. A need the file does not bring is refused, never prompted for.

**Issues:**

- [porelo-host#HO37](https://github.com/porelo-ai/porelo-host/blob/main/issues/README.md#ho37) (Finished)
- [porelo-frontend#F199](https://github.com/porelo-ai/porelo-frontend/blob/main/issues/README.md#f199) (Finished)
- [porelo-frontend#F195](https://github.com/porelo-ai/porelo-frontend/blob/main/issues/README.md#f195) (Finished)
- [porelo-frontend#F196](https://github.com/porelo-ai/porelo-frontend/blob/main/issues/README.md#f196) (Finished)

## Covers

- porelo-project#PJ51

## Data

```json
{
  "id": "ST258",
  "title": "The host's secrets from one file, typed as one master password",
  "state": "Finished",
  "gain": "A person can rent a host with its secrets taken from one file and typed as one master password on the desktop.",
  "test": "Rent a host from a drive holding only `machine.yml` and the bundle; on the desktop type the master password once, at the host the drive's login; the host comes up with its VPN key in `/etc/wireguard` and its tokens and vault keys from the file, and no secret lies on the drive. A need the file does not bring is refused, never prompted for.",
  "priority": "Highest",
  "issues": [
    "porelo-host#HO37",
    "porelo-frontend#F199",
    "porelo-frontend#F195",
    "porelo-frontend#F196"
  ],
  "tested": "2026-10-28"
}
```
