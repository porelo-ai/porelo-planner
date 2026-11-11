# ST104 — Keep accounts and projects apart

**Priority:** 🟰 Medium · **State:** Idea

**What a user gains.** Accounts and projects stay apart, so a transport reaches only a declared destination and one project cannot reach another's.

**The test.** Run a transport against a declared and an undeclared destination; only the first works. Try reading Porelo data and review directories as a second account; access fails. Use project/task pairs a-b/c and a/b-c; state and credentials stay separate. Change PATH; privileged hooks still use their fixed programs.

**Issues:**

- [porelo#B199](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b199) (Accepted)
- [porelo#B50](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b50) (Review)
- [porelo#B246](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b246) (Accepted)
- [porelo#B249](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b249) (Idea)

## Notes

- The move acceptance in release 0.6 must be complete before this sprint starts.

## Data

```json
{
  "id": "ST104",
  "title": "Keep accounts and projects apart",
  "state": "Idea",
  "gain": "Accounts and projects stay apart, so a transport reaches only a declared destination and one project cannot reach another's.",
  "test": "Run a transport against a declared and an undeclared destination; only the first works. Try reading Porelo data and review directories as a second account; access fails. Use project/task pairs a-b/c and a/b-c; state and credentials stay separate. Change PATH; privileged hooks still use their fixed programs.",
  "priority": "Medium",
  "issues": [
    "porelo#B199",
    "porelo#B50",
    "porelo#B246",
    "porelo#B249"
  ],
  "tested": null
}
```
