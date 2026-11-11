# ST253 — Codex answers without holding real tokens

**Priority:** ⏫ Highest · **State:** Finished (2026-10-28)

**What a user gains.** A person can ask a Codex task a question through the ChatGPT sign-in while the container holds none of the real tokens.

**The test.** Start a Codex task with the ChatGPT sign-in and ask it a question. Search the container for the real tokens; none are present. Interrupt a refresh after rotation and resume; the new refresh token must still work.

**Issues:**

- [porelo#P11](https://github.com/porelo-ai/porelo/blob/main/issues/providers/README.md#p11) (Finished)

## Data

```json
{
  "id": "ST253",
  "title": "Codex answers without holding real tokens",
  "state": "Finished",
  "gain": "A person can ask a Codex task a question through the ChatGPT sign-in while the container holds none of the real tokens.",
  "test": "Start a Codex task with the ChatGPT sign-in and ask it a question. Search the container for the real tokens; none are present. Interrupt a refresh after rotation and resume; the new refresh token must still work.",
  "priority": "Highest",
  "issues": [
    "porelo#P11"
  ],
  "tested": "2026-10-28"
}
```
