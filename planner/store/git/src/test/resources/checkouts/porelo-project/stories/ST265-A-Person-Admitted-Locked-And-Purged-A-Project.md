# ST265 — A person admitted, locked and purged; a project registered and taken off

**Priority:** ⏫ Highest · **State:** Finished (2026-10-30)

**What a user gains.** A host admin can admit a project admin, lock or purge a person, and register or take off a project, with each person getting their own account and token.

**The test.** The host admin admits a project admin by hand, who gets an account of their own and a token of the shared homeserver, named `<host>-<account>` in the room; a locked person loses every access without a terminal, and a purge removes the account. A project registered and taken off leaves the host and its forge standing.

**Issues:**

- [porelo-message-matrix#MX44](https://github.com/porelo-ai/porelo-message-matrix/blob/main/issues/README.md#mx44) (Finished)
- [porelo#B312](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b312) (Finished)
- [porelo#B315](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b315) (Finished)

## Covers

- porelo-project#PJ78

## Data

```json
{
  "id": "ST265",
  "title": "A person admitted, locked and purged; a project registered and taken off",
  "state": "Finished",
  "gain": "A host admin can admit a project admin, lock or purge a person, and register or take off a project, with each person getting their own account and token.",
  "test": "The host admin admits a project admin by hand, who gets an account of their own and a token of the shared homeserver, named `<host>-<account>` in the room; a locked person loses every access without a terminal, and a purge removes the account. A project registered and taken off leaves the host and its forge standing.",
  "priority": "Highest",
  "issues": [
    "porelo-message-matrix#MX44",
    "porelo#B312",
    "porelo#B315"
  ],
  "tested": "2026-10-30"
}
```
