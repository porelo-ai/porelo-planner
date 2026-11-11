# ST144 — Understand where a binding setting came from

**Priority:** 🔽 Low · **State:** Accepted

**What a user gains.** A person can see where a binding setting came from, reading the rule chain and the effective providers.

**The test.** Open machine and project settings, read their rule chain and effective providers, and try changing a locked setting. The interface explains its origin and the permitted next step; the daemon still refuses the forbidden change.

**Issues:**

- [porelo-frontend#F62](https://github.com/porelo-ai/porelo-frontend/blob/main/issues/README.md#f62) (Estimated)
- [porelo-frontend#F123](https://github.com/porelo-ai/porelo-frontend/blob/main/issues/README.md#f123) (Accepted)

## Data

```json
{
  "id": "ST144",
  "title": "Understand where a binding setting came from",
  "state": "Accepted",
  "gain": "A person can see where a binding setting came from, reading the rule chain and the effective providers.",
  "test": "Open machine and project settings, read their rule chain and effective providers, and try changing a locked setting. The interface explains its origin and the permitted next step; the daemon still refuses the forbidden change.",
  "priority": "Low",
  "issues": [
    "porelo-frontend#F62",
    "porelo-frontend#F123"
  ],
  "tested": null
}
```
