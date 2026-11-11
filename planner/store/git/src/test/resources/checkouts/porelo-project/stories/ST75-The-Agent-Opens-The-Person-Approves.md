# ST75 — The agent opens, the person approves

**Priority:** 🟰 Medium · **State:** Analyzed

**What a user gains.** The agent opens the pull request from its own forge account and only the person approves it, so authorship and approval stay separate.

**The test.** A task's approved work is a pull request opened by the agent's own forge account; the person approves it from the interface with their own account; an approval with the agent's account is refused.

**Issues:**

- [porelo-frontend#F190](https://github.com/porelo-ai/porelo-frontend/blob/main/issues/README.md#f190) (Analyzed)

## Covers

- porelo-project#PJ72

## Data

```json
{
  "id": "ST75",
  "title": "The agent opens, the person approves",
  "state": "Analyzed",
  "gain": "The agent opens the pull request from its own forge account and only the person approves it, so authorship and approval stay separate.",
  "test": "A task's approved work is a pull request opened by the agent's own forge account; the person approves it from the interface with their own account; an approval with the agent's account is refused.",
  "priority": "Medium",
  "issues": [
    "porelo-frontend#F190"
  ],
  "tested": null
}
```
