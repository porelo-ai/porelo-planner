# ST69 — A read-only OMP reviewer started explicitly

**Priority:** 🟰 Medium · **State:** Analyzed

**What a user gains.** A person can start OMP as a read-only reviewer with a chosen thinking level, and the agent gets exactly those values.

**The test.** Start OMP with read-only mode and a thinking level; the agent receives those values. An unknown mode or level is refused before the start. Try a reviewer push or approval; the host still refuses it.

**Issues:**

- [porelo#B101](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b101) (Analyzed)
- [porelo-omp#OM19](https://github.com/porelo-ai/porelo-omp/blob/main/issues/README.md#om19) (Analyzed)

## Data

```json
{
  "id": "ST69",
  "title": "A read-only OMP reviewer started explicitly",
  "state": "Analyzed",
  "gain": "A person can start OMP as a read-only reviewer with a chosen thinking level, and the agent gets exactly those values.",
  "test": "Start OMP with read-only mode and a thinking level; the agent receives those values. An unknown mode or level is refused before the start. Try a reviewer push or approval; the host still refuses it.",
  "priority": "Medium",
  "issues": [
    "porelo#B101",
    "porelo-omp#OM19"
  ],
  "tested": null
}
```
