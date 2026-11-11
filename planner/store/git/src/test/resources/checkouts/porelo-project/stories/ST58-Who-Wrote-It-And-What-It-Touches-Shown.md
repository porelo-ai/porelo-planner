# ST58 — Who wrote it and what it touches, shown on waiting work

**Priority:** 🟰 Medium · **State:** Idea

**What a user gains.** A person sees on waiting work, and on each comment and finding, who wrote it and what it touches, as derived by the daemon.

**The test.** Waiting work, each comment and each finding carry the source level the daemon derived from authenticated data (E0, E1, I1, A) and the risk class of the change (C0-C3), shown in `porelo gate review`; a level claimed in the content changes nothing, and nothing is refused by them yet.

**Issues:**

- [porelo#B323](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b323) (Idea)

## Covers

- porelo-project#PJ73

## Data

```json
{
  "id": "ST58",
  "title": "Who wrote it and what it touches, shown on waiting work",
  "state": "Idea",
  "gain": "A person sees on waiting work, and on each comment and finding, who wrote it and what it touches, as derived by the daemon.",
  "test": "Waiting work, each comment and each finding carry the source level the daemon derived from authenticated data (E0, E1, I1, A) and the risk class of the change (C0-C3), shown in `porelo gate review`; a level claimed in the content changes nothing, and nothing is refused by them yet.",
  "priority": "Medium",
  "issues": [
    "porelo#B323"
  ],
  "tested": null
}
```
