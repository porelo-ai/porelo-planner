# PL171 — A Fence Left Open Never Hides a Section From a Write

```json
{
  "$schema": "issue-1.json",
  "id": "PL171",
  "title": "A Fence Left Open Never Hides a Section From a Write",
  "state": "Idea",
  "size": "S",
  "priority": "High",
  "rank": 88,
  "blockedBy": ["PL164"],
  "covers": "A stage result written into a GitHub issue's body never deletes a section a person wrote, even when a code fence was left open by hand and a later code block closes it"
}
```

**Why this size.** S: one class of the GitHub store, `BodySections`, and its tests; no contract.

**What must be true.** A stage result writes only the sections it names, and a person's other sections stay as they were, even after a code fence was left open in the body by hand.

## Plan

- Today `BodySections` reads code fences as Markdown does. A fence that is never closed counts as no fence, so the headings after it stay sections. But a fence left open by hand, followed later by a closed code block, pairs with that block's opening line, as CommonMark reads it. The sections between them then count as code, and a write of the section above them deletes them with it.
- A write checks what it would remove. If a code block opens inside the replaced section and closes only after a
  line shaped as a second-level heading, the write refuses, names the section and writes nothing. A closed code
  block that holds such a line wholly inside the section is still replaced, as today. The task's result is then
  refused as not savable, never saved by half.
- Red first: `BodySectionsTest`, a body with a fence opened under *Plan*, then *Acceptance*, then a closed code block under *Notes*; writing *Plan* is refused and the body stays byte for byte.

## Acceptance

- The test above, seen red before the guard.
