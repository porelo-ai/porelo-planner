# PL172 — Every Item Shown With Its Warnings, in Every Store

```json
{
  "$schema": "issue-1.json",
  "id": "PL172",
  "title": "Every Item Shown With Its Warnings, in Every Store",
  "state": "Analyzed",
  "size": "M",
  "priority": "High",
  "rank": 89,
  "blockedBy": ["PL164"],
  "covers": "The store API never lets a store leave a story or issue out: what a store cannot read in an item is a warning on it, which the board shows by an icon, and a value the item states counts over anything derived from its text"
}
```

**Why this size.** M: a contract change the window reads (`warnings` on `Story` and `Issue` in `planner.yaml`),
the store API's rules, the Markdown store's faults turned into warnings, the work rules and the screen's icon.

**What must be true.** A person sees every story and issue of the project on the board, whichever store keeps it.
An item the planner cannot fully read carries a warning icon that says what is wrong. A later tracker's store keeps
the same rules by building against the API.

## Plan

- The rules stand in the store API (`PlanningStore`, `Board`, `Story`, `Issue`), so every store keeps them:
  - **An item is never left out.** A store hands on every item it finds. What it cannot read is a warning on the
    item, not a fault of the project. Only what has no place on the board at all stays a project fault, such as a
    file that names no item.
  - **A stated value counts.** The state, priority and the sums an item states are shown as stated. A store never
    checks them against the item's text or history, and never warns because they differ.
- `Story` and `Issue` gain `warnings`, a list of `{kind, text}`. `kind` is an open name, such as `StateOutsideWorkflow`,
  `NoPriority`, `IdTwice`, `ParentOutsideProject`, `BlockerOutsideProject`, `WrongParent` or `BodyNotSectioned`.
  `text` is one fixed sentence that never repeats a person's words. `planner.yaml` describes them.
- How a warned item is placed, the same in every store: a state outside the workflow shows the item in its own
  column at the board's end, named after the value. An id given twice shows both items, both warned. A parent or
  blocker outside the project is dropped from the item, which is warned.
- The Markdown store (`GitStore`) follows. Today it leaves out an issue whose state the workflow does not know, an
  issue in two repositories, a story whose state cannot be told, and a story issue that is no issue of the
  project's repositories. Each becomes a warning on the item.
- A work rule never starts work on an item whose warning touches its state, parent or blockers, so a hand edit
  cannot slip work past a rule. The proposal names such an item as held, with the warning's text.
- The mock board carries one item of each kind for the window's tests. The screen shows an icon on the card and in
  the dialog, with the texts as a tooltip.
- Red first: a `GitStoreReadTest` case per former left-out item shows it on the board, warned. A controller test
  shows `warnings` served as described. A `WorkProposal` test shows a warned item held. A screen test shows the
  icon and its text.

## Acceptance

- On the VM, an item of each kind is shown with its warning in the window, and no work starts on one whose state
  is in doubt.
