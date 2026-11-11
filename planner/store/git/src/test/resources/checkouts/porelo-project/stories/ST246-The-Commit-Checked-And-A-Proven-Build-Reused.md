# ST246 — The commit checked and a proven build reused

**Priority:** ⏫ Highest · **State:** Finished (2026-10-27)

**What a user gains.** A commit is checked locally and in the build, a proven build is reused for the same tree, and a different tree or old result is refused.

**The test.** Check a valid and an invalid commit locally and in the build; the invalid one is refused by name. Change only Actions and confirm no full Build starts. Reuse fresh legs for the same tree; a different tree or an old result must be refused.

**Issues:**

- [porelo-buildtools#BT17](https://github.com/porelo-ai/porelo-buildtools/blob/main/issues/README.md#bt17) (Finished)
- [porelo-buildtools#BT34](https://github.com/porelo-ai/porelo-buildtools/blob/main/issues/README.md#bt34) (Finished)
- [porelo-buildtools#BT13](https://github.com/porelo-ai/porelo-buildtools/blob/main/issues/README.md#bt13) (Finished)
- [porelo-claude-code#CC34](https://github.com/porelo-ai/porelo-claude-code/blob/main/issues/README.md#cc34) (Finished)
- [porelo-pi#PI31](https://github.com/porelo-ai/porelo-pi/blob/main/issues/README.md#pi31) (Finished)

## Data

```json
{
  "id": "ST246",
  "title": "The commit checked and a proven build reused",
  "state": "Finished",
  "gain": "A commit is checked locally and in the build, a proven build is reused for the same tree, and a different tree or old result is refused.",
  "test": "Check a valid and an invalid commit locally and in the build; the invalid one is refused by name. Change only Actions and confirm no full Build starts. Reuse fresh legs for the same tree; a different tree or an old result must be refused.",
  "priority": "Highest",
  "issues": [
    "porelo-buildtools#BT17",
    "porelo-buildtools#BT34",
    "porelo-buildtools#BT13",
    "porelo-claude-code#CC34",
    "porelo-pi#PI31"
  ],
  "tested": "2026-10-27"
}
```
