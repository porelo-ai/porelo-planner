# PL176 — An Offer to Correct Each Warning

```json
{
  "$schema": "issue-1.json",
  "id": "PL176",
  "title": "An Offer to Correct Each Warning",
  "state": "Analyzed",
  "size": "M",
  "priority": "Medium",
  "rank": 93,
  "blockedBy": ["PL172"],
  "covers": "Each warning on an item, in every store, names the one change that corrects it, which the person confirms on the board"
}
```

**Why this size.** M: the store API names a correction per warning kind, each carried out over a store's existing
writes (a move, a priority, a parent), with a preview and a confirm in the screen.

**What must be true.** A person who sees a warning can set it right from the board with one confirmed step,
without going to GitHub.

## Plan

- Each warning of [PL172](README.md#pl172) names its correction in the store API (`correction` beside `kind`), so
  every store offers the same, and the window offers it beside the icon:
  - `StatusOutsideWorkflow`: a move to a state the person picks;
  - `NoPriority`: a priority to set;
  - `IdTwice`: the id cleared on the item the person picks;
  - `WrongParent`: the id set to match the parent the item stands under;
  - `ParentOutsideProject` and `BlockerOutsideProject`: the item added to the project, or the link removed;
  - `BodyNotSectioned`: a link to the item where it is kept, with what to repair, since the planner never
    rewrites a person's text.
- Each correction is previewed and confirmed like a move, and saved through the store's existing writes. It never
  touches a body.
- A store that cannot carry out a correction answers it as not offered, never as a write that half happens.
- Red first: a controller test per correction kind, over the Markdown store and over the GitHub store with a fake
  client.

## Acceptance

- On the VM, each warning of a board edited by hand is corrected from the window.
