# ST256 — The secrets file made once and opened with KeePassXC

**Priority:** ⏫ Highest · **State:** Finished (2026-10-28)

**What a user gains.** A person can make the secrets file once from the interface's terminal and open it with KeePassXC, with no secret appearing in the interface, files beside it or logs.

**The test.** On this computer, without renting: the interface's terminal makes `secrets.kdbx` where the person chooses (offered `~/Porelo/secrets.kdbx`) with the groups `shared` and `hosts/<project>` after one master password; KeePassXC opens it with that password and shows the entries; the interface's package brought `keepassxc-cli` with it, and no secret appears in the interface, a file beside it or a log.

**Issues:**

- [porelo-frontend#F198](https://github.com/porelo-ai/porelo-frontend/blob/main/issues/README.md#f198) (Finished)

## Covers

- porelo-project#PJ51

## Data

```json
{
  "id": "ST256",
  "title": "The secrets file made once and opened with KeePassXC",
  "state": "Finished",
  "gain": "A person can make the secrets file once from the interface's terminal and open it with KeePassXC, with no secret appearing in the interface, files beside it or logs.",
  "test": "On this computer, without renting: the interface's terminal makes `secrets.kdbx` where the person chooses (offered `~/Porelo/secrets.kdbx`) with the groups `shared` and `hosts/<project>` after one master password; KeePassXC opens it with that password and shows the entries; the interface's package brought `keepassxc-cli` with it, and no secret appears in the interface, a file beside it or a log.",
  "priority": "Highest",
  "issues": [
    "porelo-frontend#F198"
  ],
  "tested": "2026-10-28"
}
```
