# ST59 — A report outside the profile refused

**Priority:** 🟰 Medium · **State:** Analyzed

**What a user gains.** Reports with unsafe locations, fixes in the wrong file or other disallowed content are refused, so a bad report cannot do harm.

**The test.** Reports with a `file://` or `../` location, a fix in another file than its anchor, an external property file, a bidirectional control character, or more results than the limit are each refused, naming the element; nothing of them is shown or kept.

**Issues:**

- [porelo-message-sluice#SL24](https://github.com/porelo-ai/porelo-message-sluice/blob/main/issues/README.md#sl24) (Analyzed)

## Data

```json
{
  "id": "ST59",
  "title": "A report outside the profile refused",
  "state": "Analyzed",
  "gain": "Reports with unsafe locations, fixes in the wrong file or other disallowed content are refused, so a bad report cannot do harm.",
  "test": "Reports with a `file://` or `../` location, a fix in another file than its anchor, an external property file, a bidirectional control character, or more results than the limit are each refused, naming the element; nothing of them is shown or kept.",
  "priority": "Medium",
  "issues": [
    "porelo-message-sluice#SL24"
  ],
  "tested": null
}
```
