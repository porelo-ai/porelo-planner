# ST268 — Each person's one key and card, kept in the registry

**Priority:** ⏫ Highest · **State:** Finished (2026-11-02)

**What a user gains.** Each person's key and card are made once and kept in the registry, and a card that does not match the forge account or a revoked key is refused with the reason.

**The test.** A person's key and card are made once and filed under people/ with the key on their forge account; a card under another login, an unlisted key or a successor signed by a revoked key is refused with a reason, a changed key reaches the registry and hosts, and a lost key is replaced by an admin.

**Issues:**

- [porelo#B337](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b337) (Finished)
- [porelo#B339](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b339) (Finished)
- [porelo#B338](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b338) (Finished)
- [porelo#B356](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b356) (Finished)
- [porelo#B346](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b346) (Finished)
- [porelo-frontend#F268](https://github.com/porelo-ai/porelo-frontend/blob/main/issues/README.md#f268) (Finished)
- [porelo-frontend#F280](https://github.com/porelo-ai/porelo-frontend/blob/main/issues/README.md#f280) (Finished)
- [porelo-forge-clients#FC04](https://github.com/porelo-ai/porelo-forge-clients/blob/main/issues/README.md#fc04) (Finished)
- [porelo-forge-clients#FC07](https://github.com/porelo-ai/porelo-forge-clients/blob/main/issues/README.md#fc07) (Finished)

## Covers

- porelo-project#PJ83

## Data

```json
{
  "id": "ST268",
  "title": "Each person's one key and card, kept in the registry",
  "state": "Finished",
  "gain": "Each person's key and card are made once and kept in the registry, and a card that does not match the forge account or a revoked key is refused with the reason.",
  "test": "A person's key and card are made once and filed under people/ with the key on their forge account; a card under another login, an unlisted key or a successor signed by a revoked key is refused with a reason, a changed key reaches the registry and hosts, and a lost key is replaced by an admin.",
  "priority": "Highest",
  "issues": [
    "porelo#B337",
    "porelo#B339",
    "porelo#B338",
    "porelo#B356",
    "porelo#B346",
    "porelo-frontend#F268",
    "porelo-frontend#F280",
    "porelo-forge-clients#FC04",
    "porelo-forge-clients#FC07"
  ],
  "tested": "2026-11-02"
}
```
