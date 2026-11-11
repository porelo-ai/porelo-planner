# ST23 — The room and the usage records outlive the host

**Priority:** ⏫ Highest · **State:** Backlog

**What a user gains.** The room with its messages and the usage records survive taking the host down and bringing it up again, so history is not lost.

**The test.** After `down` and a new `up` the room is there with its messages, and so are the records.

**Issues:**

- [porelo-message-matrix#MX24](https://github.com/porelo-ai/porelo-message-matrix/blob/main/issues/README.md#mx24) (Review)
- [porelo-message-matrix#MX36](https://github.com/porelo-ai/porelo-message-matrix/blob/main/issues/README.md#mx36) (Review)
- [porelo-message-matrix#MX39](https://github.com/porelo-ai/porelo-message-matrix/blob/main/issues/README.md#mx39) (Review)
- [porelo-host#HO07](https://github.com/porelo-ai/porelo-host/blob/main/issues/README.md#ho07) (Backlog)
- [porelo#B231](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b231) (Review)

## Covers

- porelo-project#PJ45

## Data

```json
{
  "id": "ST23",
  "title": "The room and the usage records outlive the host",
  "state": "Backlog",
  "gain": "The room with its messages and the usage records survive taking the host down and bringing it up again, so history is not lost.",
  "test": "After `down` and a new `up` the room is there with its messages, and so are the records.",
  "priority": "Highest",
  "issues": [
    "porelo-message-matrix#MX24",
    "porelo-message-matrix#MX36",
    "porelo-message-matrix#MX39",
    "porelo-host#HO07",
    "porelo#B231"
  ],
  "tested": null
}
```
