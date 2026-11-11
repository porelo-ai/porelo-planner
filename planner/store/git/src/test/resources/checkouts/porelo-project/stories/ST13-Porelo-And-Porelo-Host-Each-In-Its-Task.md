# ST13 — porelo and porelo-host each in its task, with a way back

**Priority:** ⏫ Highest · **State:** Idea

**What a user gains.** Porelo and the host collection can each be built by an agent in its own task on the host, with a way back if the move needs undoing.

**The test.** porelo's agent builds porelo in a task and porelo-host's agent its collection; each works on this machine again if a fault in Porelo or the host's setup stops it.

**Issues:**

- [porelo-host#HO40](https://github.com/porelo-ai/porelo-host/blob/main/issues/README.md#ho40) (Analyzed)
- [porelo-host#HO39](https://github.com/porelo-ai/porelo-host/blob/main/issues/README.md#ho39) (Analyzed)
- [porelo#B305](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b305) (Idea)

## Data

```json
{
  "id": "ST13",
  "title": "porelo and porelo-host each in its task, with a way back",
  "state": "Idea",
  "gain": "Porelo and the host collection can each be built by an agent in its own task on the host, with a way back if the move needs undoing.",
  "test": "porelo's agent builds porelo in a task and porelo-host's agent its collection; each works on this machine again if a fault in Porelo or the host's setup stops it.",
  "priority": "Highest",
  "issues": [
    "porelo-host#HO40",
    "porelo-host#HO39",
    "porelo#B305"
  ],
  "tested": null
}
```
