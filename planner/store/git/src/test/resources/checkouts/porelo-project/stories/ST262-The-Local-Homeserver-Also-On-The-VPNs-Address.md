# ST262 — The local homeserver also on the VPN's address

**Priority:** ⏫ Highest · **State:** Finished (2026-10-28)

**What a user gains.** A person with the VPN up can reach the local homeserver on the VPN's address under its own certificate.

**The test.** With the VPN up, the homeserver answers on `https://192.0.2.2:<port>` under its own certificate and still on `127.0.0.1`; with the VPN down, the loopback listener keeps running and nothing listens on another address.

**Issues:**

- [porelo-message-matrix#MX42](https://github.com/porelo-ai/porelo-message-matrix/blob/main/issues/README.md#mx42) (Finished)
- [porelo-message-matrix#MX41](https://github.com/porelo-ai/porelo-message-matrix/blob/main/issues/README.md#mx41) (Finished)
- [porelo#B300](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b300) (Finished)

## Data

```json
{
  "id": "ST262",
  "title": "The local homeserver also on the VPN's address",
  "state": "Finished",
  "gain": "A person with the VPN up can reach the local homeserver on the VPN's address under its own certificate.",
  "test": "With the VPN up, the homeserver answers on `https://192.0.2.2:<port>` under its own certificate and still on `127.0.0.1`; with the VPN down, the loopback listener keeps running and nothing listens on another address.",
  "priority": "Highest",
  "issues": [
    "porelo-message-matrix#MX42",
    "porelo-message-matrix#MX41",
    "porelo#B300"
  ],
  "tested": "2026-10-28"
}
```
