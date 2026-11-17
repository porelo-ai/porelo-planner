# PL162 — A Stage Result's Repair: Smaller Findings

```json
{
  "$schema": "issue-1.json",
  "id": "PL162",
  "title": "A Stage Result's Repair: Smaller Findings",
  "state": "Idea",
  "size": "-",
  "priority": "Medium",
  "rank": 1,
  "blockedBy": [],
  "covers": "The smaller findings of the reading of the repair of a handed-in stage result: a split item that starts with #, the plan's length after its headings are lowered, and the order's words on line breaks and deeper headings"
}
```

**What must be true.** A stage result that the planner can repair is never refused for a part its own repair made,
and the order's text says what the planner repairs and what it refuses.

## Found

- A list item over its bound is split at its sentence ends. When a sentence after a cut starts with `#`, such as
  `aaa… . #12 is blocked …`, the new item starts with `#`, which the store refuses, so the result is refused for a
  part the repair made. Cutting before such a sentence is avoided, or the sentence is joined to the part before it.
- The plan's length is checked before its first- and second-level headings are lowered to `###`, so a saved plan can
  be longer than the bound the order names: a plan of 10,000 lines of `#` becomes 30,000 characters. The overshoot
  is bounded and the store sets no length of its own.
- The order's text says no item holds a line break, while the planner now turns a line break in an item into a
  space; and it says the plan starts at `###` without saying that deeper headings are allowed.
- Test gaps: a list of exactly the most items with one item split, a surrogate pair near an item's bound, line
  breaks other than `\n`, and the estimate order's bounds.
