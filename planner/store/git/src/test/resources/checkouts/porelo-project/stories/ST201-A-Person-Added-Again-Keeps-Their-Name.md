# ST201 — A person added again keeps their name

**Priority:** 🔽 Low · **State:** Idea

**What a user gains.** A person removed from a prepared host and added again under the same user name keeps their Porelo data and name.

**The test.** Remove a person from a prepared host and add them again under the same user name: their Porelo sets its own password with the one-time passwords it was handed, rejoins the room under `<host>-<user>`, and no one else can use those passwords.

**Issues:**

- [porelo-message-matrix#MX53](https://github.com/porelo-ai/porelo-message-matrix/blob/main/issues/README.md#mx53) (Rejected)

## Data

```json
{
  "id": "ST201",
  "title": "A person added again keeps their name",
  "state": "Idea",
  "gain": "A person removed from a prepared host and added again under the same user name keeps their Porelo data and name.",
  "test": "Remove a person from a prepared host and add them again under the same user name: their Porelo sets its own password with the one-time passwords it was handed, rejoins the room under `<host>-<user>`, and no one else can use those passwords.",
  "priority": "Low",
  "issues": [
    "porelo-message-matrix#MX53"
  ],
  "tested": null
}
```
