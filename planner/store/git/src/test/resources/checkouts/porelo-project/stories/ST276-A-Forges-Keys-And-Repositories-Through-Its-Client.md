# ST276 — A forge's keys and repositories through its client

**Priority:** ⏫ Highest · **State:** Finished (2026-11-02)

**What a user gains.** A person's public SSH keys and repositories on Forgejo and GitHub are read, listed and managed through the forge's client, without a token for public keys.

**The test.** On Forgejo and on GitHub the client reads a person's public SSH keys without a token, lists, adds and deletes the signed-in person's own keys, tells whether a user owns an organization, and reads and creates a repository in it, against answers recorded from the real forge.

**Issues:**

- [porelo-forge-clients#FC02](https://github.com/porelo-ai/porelo-forge-clients/blob/main/issues/README.md#fc02) (Finished)
- [porelo-forge-clients#FC05](https://github.com/porelo-ai/porelo-forge-clients/blob/main/issues/README.md#fc05) (Finished)
- [porelo-forge-clients#FC06](https://github.com/porelo-ai/porelo-forge-clients/blob/main/issues/README.md#fc06) (Finished)

## Data

```json
{
  "id": "ST276",
  "title": "A forge's keys and repositories through its client",
  "state": "Finished",
  "gain": "A person's public SSH keys and repositories on Forgejo and GitHub are read, listed and managed through the forge's client, without a token for public keys.",
  "test": "On Forgejo and on GitHub the client reads a person's public SSH keys without a token, lists, adds and deletes the signed-in person's own keys, tells whether a user owns an organization, and reads and creates a repository in it, against answers recorded from the real forge.",
  "priority": "Highest",
  "issues": [
    "porelo-forge-clients#FC02",
    "porelo-forge-clients#FC05",
    "porelo-forge-clients#FC06"
  ],
  "tested": "2026-11-02"
}
```
