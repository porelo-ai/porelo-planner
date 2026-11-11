# ST150 — Accept only admitted people as people

**Priority:** 🔽 Low · **State:** Analyzed

**What a user gains.** Only admitted people count as people in the room, so a machine signed in to the central homeserver cannot pass as one.

**The test.** Sign two machines in to the central homeserver. Have an admitted person write in the room and directly. Have a foreign task claim to be a person and try a task-to-task direct chat. Only the admitted person is delivered as a person; forbidden paths are refused.

**Issues:**

- [porelo#B86](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b86) (Analyzed)
- [porelo#B122](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b122) (Analyzed)

## Notes

- The move acceptance in release 0.6 must be complete first.

## Data

```json
{
  "id": "ST150",
  "title": "Accept only admitted people as people",
  "state": "Analyzed",
  "gain": "Only admitted people count as people in the room, so a machine signed in to the central homeserver cannot pass as one.",
  "test": "Sign two machines in to the central homeserver. Have an admitted person write in the room and directly. Have a foreign task claim to be a person and try a task-to-task direct chat. Only the admitted person is delivered as a person; forbidden paths are refused.",
  "priority": "Low",
  "issues": [
    "porelo#B86",
    "porelo#B122"
  ],
  "tested": null
}
```
