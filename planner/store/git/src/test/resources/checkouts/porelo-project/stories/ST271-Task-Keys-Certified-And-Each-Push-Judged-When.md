# ST271 — Task keys certified, and each push judged when it arrives

**Priority:** ⏫ Highest · **State:** Finished (2026-11-02)

**What a user gains.** Task keys are certified by the person who started them, and each push is judged when it arrives, so a person can trust who a push came from.

**The test.** A task signs with its own key certified by the person who started it; the host records the certificate and the task's forge token, judges each push by its arrival, and refuses one by an expired certificate or a revoked key.

**Issues:**

- [porelo#B335](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b335) (Finished)
- [porelo#B334](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b334) (Finished)
- [porelo-host#HO71](https://github.com/porelo-ai/porelo-host/blob/main/issues/README.md#ho71) (Finished)
- [porelo-host#HO76](https://github.com/porelo-ai/porelo-host/blob/main/issues/README.md#ho76) (Finished)
- [porelo#B341](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b341) (Finished)
- [porelo#B343](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b343) (Finished)
- [porelo#B344](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b344) (Finished)
- [porelo#B362](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b362) (Finished)
- [porelo#B363](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b363) (Finished)
- [porelo#B372](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b372) (Finished)
- [porelo-frontend#F277](https://github.com/porelo-ai/porelo-frontend/blob/main/issues/README.md#f277) (Finished)

## Covers

- porelo-project#PJ83

## Data

```json
{
  "id": "ST271",
  "title": "Task keys certified, and each push judged when it arrives",
  "state": "Finished",
  "gain": "Task keys are certified by the person who started them, and each push is judged when it arrives, so a person can trust who a push came from.",
  "test": "A task signs with its own key certified by the person who started it; the host records the certificate and the task's forge token, judges each push by its arrival, and refuses one by an expired certificate or a revoked key.",
  "priority": "Highest",
  "issues": [
    "porelo#B335",
    "porelo#B334",
    "porelo-host#HO71",
    "porelo-host#HO76",
    "porelo#B341",
    "porelo#B343",
    "porelo#B344",
    "porelo#B362",
    "porelo#B363",
    "porelo#B372",
    "porelo-frontend#F277"
  ],
  "tested": "2026-11-02"
}
```
