# PL47 — A Story's Size Beside Its Priority

```json
{
  "$schema": "issue-1.json",
  "id": "PL47",
  "title": "A Story's Size Beside Its Priority",
  "state": "Idea",
  "size": "S",
  "priority": "Medium",
  "rank": 30,
  "blockedBy": [],
  "covers": "A story carries its own size, XS to XL, set when it is estimated; the board's answer carries it beside its priority, and an issue's size, which the answer already carries, shows the same way"
}
```

**Size:** S - one optional field in the story's head and its schema, read into the board's answer; the badge itself is the window's.

**What must be true.** A person sees each story's size beside its priority on the board, as each issue's; a story's size is its own, given when it is estimated, not added up from its issues.

## Plan

- Today the board's `Issue` carries `size` (XS to XL or -); a story carries none, and the story schema has no field for it.
- An optional `size` in the story's head, XS to XL or -, set at estimation; the board's `Story` carries it, `-` where none is set. A story's size is not derived from its issues: sizes do not add up, and an estimate of the whole says what the parts do not.
- Red first: a story whose head says M is answered with size M.

## Acceptance

- A story's size from its head is in the board's answer; a story without one is answered with `-`.
