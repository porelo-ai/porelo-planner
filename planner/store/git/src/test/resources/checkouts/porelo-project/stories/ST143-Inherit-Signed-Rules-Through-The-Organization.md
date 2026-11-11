# ST143 — Inherit signed rules through the organization

**Priority:** 🔽 Low · **State:** Accepted

**What a user gains.** Settings inherit through signed organization, team and project rules, and a project can narrow a value but cannot widen it.

**The test.** Connect organization, team and project settings and narrow an allowed value. Try to widen a fixed value, alter a signature and insert an unrelated project. The permitted narrowing works; all three bypasses are refused by name.

**Issues:**

- [porelo#B198](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b198) (Accepted)

## Notes

- Pin An Organization Root; Verify The Settings Chain; Inherit Organization Settings; Enforce Fixed And Narrow-Only Settings.

## Data

```json
{
  "id": "ST143",
  "title": "Inherit signed rules through the organization",
  "state": "Accepted",
  "gain": "Settings inherit through signed organization, team and project rules, and a project can narrow a value but cannot widen it.",
  "test": "Connect organization, team and project settings and narrow an allowed value. Try to widen a fixed value, alter a signature and insert an unrelated project. The permitted narrowing works; all three bypasses are refused by name.",
  "priority": "Low",
  "issues": [
    "porelo#B198"
  ],
  "tested": null
}
```
