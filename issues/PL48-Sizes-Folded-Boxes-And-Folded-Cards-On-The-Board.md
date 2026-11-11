# PL48 — Sizes, Folded Boxes And Folded Cards On The Board

```json
{
  "$schema": "issue-1.json",
  "id": "PL48",
  "title": "Sizes, Folded Boxes And Folded Cards On The Board",
  "state": "Accepted",
  "size": "M",
  "priority": "Medium",
  "rank": 31,
  "blockedBy": [],
  "covers": "Every story card and issue row shows its size as a badge beside its priority; a state's box folds to its heading with name and count, its content hidden and the column as wide as before; a single story card folds to its heading with number and priority"
}
```

**Size:** M - three changes in the screen package alone, each with its widget test; the story's size comes from the
board's answer once a story carries one.

**What must be true.** A person sees each story's and issue's size as a badge beside its priority, and can fold away
what they do not need: a state's box down to its heading, and a single story card down to its number and priority,
without the column changing its width.

## Plan

- **The size badge.** Today an issue row shows its size as plain text after its state, and a story shows none. A
  badge, XS to XL, stands beside the priority chip on a story card, on an issue row inside a card and on the compact
  tiles of a narrowed column; `-` shows no badge. A story's size is read from the board's answer once it carries one
  ([PL47](README.md#pl47)).
- **A folded box.** Today a state's chevron (`foldState` in `planning_board.dart`, drawn in `planning_view.dart`)
  turns its tiles into the compact one-line rows a narrowed column shows. It folds the box to its heading instead,
  name and count kept, its tiles and issues hidden; the column keeps its width. Only the column's own chevron
  narrows the column.
- **A folded card.** A story card gets a control of its own that folds it to its heading, number and priority; its
  text, issue list and progress bar are hidden, the cards beside it stay open. The board keeps which cards are folded
  as it keeps folded boxes.
- Red first: a widget test that folds a box finds none of its cards and the column at its full width; one that folds
  a card finds its number and not its text, and the card beside it open.

## Acceptance

- A folded box shows its heading only, at the column's width; a folded card shows its number and priority only; every
  card and issue row with a size shows it as a badge.
