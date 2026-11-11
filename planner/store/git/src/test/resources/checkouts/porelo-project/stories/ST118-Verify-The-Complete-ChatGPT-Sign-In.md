# ST118 — Verify the complete ChatGPT sign-in

**Priority:** 🔽 Low · **State:** Review

**What a user gains.** A person can sign in to ChatGPT and ask a Codex task a question, with no sign-in material inside the container.

**The test.** Ask a Codex task a question through the ChatGPT sign-in. The container contains none of the real tokens; a rotated refresh token survives an interrupted broker refresh. Check the chosen rule for the additional startup host; it is enforced.

**Issues:**

- [porelo-project#PJ54](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj54) (Finished)

## Notes

- Parent acceptance for the ChatGPT provider delivered in release 0.6; no second implementation.

## Data

```json
{
  "id": "ST118",
  "title": "Verify the complete ChatGPT sign-in",
  "state": "Review",
  "gain": "A person can sign in to ChatGPT and ask a Codex task a question, with no sign-in material inside the container.",
  "test": "Ask a Codex task a question through the ChatGPT sign-in. The container contains none of the real tokens; a rotated refresh token survives an interrupted broker refresh. Check the chosen rule for the additional startup host; it is enforced.",
  "priority": "Low",
  "issues": [
    "porelo-project#PJ54"
  ],
  "tested": null
}
```
