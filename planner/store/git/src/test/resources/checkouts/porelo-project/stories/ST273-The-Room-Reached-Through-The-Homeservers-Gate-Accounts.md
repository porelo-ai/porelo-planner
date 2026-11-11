# ST273 — The room reached through the homeserver's gate, accounts by login

**Priority:** ⏫ Highest · **State:** Finished (2026-11-02)

**What a user gains.** A person reaches the room through the homeserver's gate with Element and Porelo, with accounts by login and only through the tunnel.

**The test.** Element and Porelo reach the homeserver only through the tunnel to its gate, which knows the person by their host login; a person's accounts are named by their login, not a machine; a lock for one project takes them out of that project's rooms only; no VPN address, machine token or certificate is left.

**Issues:**

- [porelo-message-matrix#MX30](https://github.com/porelo-ai/porelo-message-matrix/blob/main/issues/README.md#mx30) (Finished)
- [porelo-message-matrix#MX56](https://github.com/porelo-ai/porelo-message-matrix/blob/main/issues/README.md#mx56) (Finished)
- [porelo-message-matrix#MX57](https://github.com/porelo-ai/porelo-message-matrix/blob/main/issues/README.md#mx57) (Finished)
- [porelo-message-matrix#MX58](https://github.com/porelo-ai/porelo-message-matrix/blob/main/issues/README.md#mx58) (Finished)
- [porelo-message-matrix#MX59](https://github.com/porelo-ai/porelo-message-matrix/blob/main/issues/README.md#mx59) (Finished)

## Covers

- porelo-project#PJ83

## Data

```json
{
  "id": "ST273",
  "title": "The room reached through the homeserver's gate, accounts by login",
  "state": "Finished",
  "gain": "A person reaches the room through the homeserver's gate with Element and Porelo, with accounts by login and only through the tunnel.",
  "test": "Element and Porelo reach the homeserver only through the tunnel to its gate, which knows the person by their host login; a person's accounts are named by their login, not a machine; a lock for one project takes them out of that project's rooms only; no VPN address, machine token or certificate is left.",
  "priority": "Highest",
  "issues": [
    "porelo-message-matrix#MX30",
    "porelo-message-matrix#MX56",
    "porelo-message-matrix#MX57",
    "porelo-message-matrix#MX58",
    "porelo-message-matrix#MX59"
  ],
  "tested": "2026-11-02"
}
```
