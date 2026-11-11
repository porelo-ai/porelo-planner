# ST288 — Import one provider and recognize OMP

**Priority:** ⏫ Highest · **State:** Finished (2026-11-06)

**What a user gains.** A person can sign Pi in to two providers, import only the chosen one, start a task, and have OMP recognized.

**The test.** Sign Pi in to two providers, import the chosen provider and start a task; the other sign-in must not be selected. Detect OMP through agent.db; its sign-in is shown as "cannot say" and left to `vault put` and `vault authorize`.

**Issues:**

- [porelo#B226](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b226) (Finished)
- [porelo-pi#PI36](https://github.com/porelo-ai/porelo-pi/blob/main/issues/README.md#pi36) (Finished)
- [porelo-omp#OM32](https://github.com/porelo-ai/porelo-omp/blob/main/issues/README.md#om32) (Finished)

## Data

```json
{
  "id": "ST288",
  "title": "Import one provider and recognize OMP",
  "state": "Finished",
  "gain": "A person can sign Pi in to two providers, import only the chosen one, start a task, and have OMP recognized.",
  "test": "Sign Pi in to two providers, import the chosen provider and start a task; the other sign-in must not be selected. Detect OMP through agent.db; its sign-in is shown as \"cannot say\" and left to `vault put` and `vault authorize`.",
  "priority": "Highest",
  "issues": [
    "porelo#B226",
    "porelo-pi#PI36",
    "porelo-omp#OM32"
  ],
  "tested": "2026-11-06"
}
```
