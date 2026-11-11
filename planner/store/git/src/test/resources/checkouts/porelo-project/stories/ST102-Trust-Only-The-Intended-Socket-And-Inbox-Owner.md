# ST102 — Trust only the intended socket and inbox owner

**Priority:** 🟰 Medium · **State:** Analyzed

**What a user gains.** On AppArmor, Porelo trusts only the intended socket and inbox owner and refuses another program's socket or a wrongly owned file.

**The test.** Start a real task on AppArmor. Offer another program's socket, an inbox file owned by the wrong account and a symbolic link. The real connection and delivery work; each planted entry or wrong socket is refused and named.

**Issues:**

- [porelo#B175](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b175) (Analyzed)
- [porelo#B184](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b184) (Estimated)

## Data

```json
{
  "id": "ST102",
  "title": "Trust only the intended socket and inbox owner",
  "state": "Analyzed",
  "gain": "On AppArmor, Porelo trusts only the intended socket and inbox owner and refuses another program's socket or a wrongly owned file.",
  "test": "Start a real task on AppArmor. Offer another program's socket, an inbox file owned by the wrong account and a symbolic link. The real connection and delivery work; each planted entry or wrong socket is refused and named.",
  "priority": "Medium",
  "issues": [
    "porelo#B175",
    "porelo#B184"
  ],
  "tested": null
}
```
