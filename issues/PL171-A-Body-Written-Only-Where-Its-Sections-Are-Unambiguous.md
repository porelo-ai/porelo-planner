# PL171 — A Body Written Only Where Its Sections Are Unambiguous

```json
{
  "$schema": "issue-1.json",
  "id": "PL171",
  "title": "A Body Written Only Where Its Sections Are Unambiguous",
  "state": "Analyzed",
  "size": "S",
  "priority": "High",
  "rank": 88,
  "blockedBy": ["PL164"],
  "covers": "A stage result is written into a GitHub issue's body only when the body splits into sections unambiguously; otherwise nothing is written and the item is warned with what to repair"
}
```

**Why this size.** S: one class of the GitHub store, `BodySections`, its writer's refusal and its tests; the
warning comes with [PL172](README.md#pl172)'s contract.

**What must be true.** A write never deletes text the planner did not place. When the planner cannot tell a body's
sections apart for certain, it writes nothing into the body and tells the person what to repair.

## Plan

- Today `BodySections` reads fences as Markdown does and counts an unclosed fence as no fence. A fence left open
  by hand, followed later by a closed code block, still pairs with that block's opening line, so the sections
  between them count as code and a write of the section above deletes them.
- Before any write, `BodySections` checks that the body is unambiguous: every fence closes within the section it
  opens in; no second-level heading stands twice; no line shaped as a second-level heading stands inside a code
  block that spans two sections; the leading paragraphs stand in the skeleton's order. A body that fails is not
  written. The stage result's other parts (its `Status`, its fields, its stage comment) are still saved, and the
  item is warned `BodyNotSectioned` with the first thing to repair, such as "the code block opened under Plan
  never closes".
- A read never fails on such a body: the board shows the item, warned.
- Red first: `BodySectionsTest`. Each ambiguous body (an unclosed fence followed by a closed block, a heading
  twice, a leading paragraph out of order) is refused and stays byte for byte. A `GitHubStoreTest` checks that
  such a result saves its fields and comment and warns the item.

## Acceptance

- The test above, seen red before the guard.
