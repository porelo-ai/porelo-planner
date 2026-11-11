# PL34 — The Board Laid Out As It Is Used

```json
{
  "$schema": "issue-1.json",
  "id": "PL34",
  "title": "The Board Laid Out As It Is Used",
  "state": "Review",
  "size": "XS",
  "priority": "High",
  "rank": 21,
  "blockedBy": [],
  "covers": "A folded column shows each story in one even row of mark, number and menu; the board's sideways scrollbar stays at the foot of the view, so a wide column's controls can be reached; the list of unsaved moves takes only the height it needs"
}
```

**Size:** XS - one view file and its style constants, no contract.

**What must be true.** Every control of the board can be seen and reached, and its rows stand even, with as many
stories as a real project has.

- A folded column shows each story in one row of the same height: the priority mark in a place of one width, the
  number, the menu. A Highest mark no longer pushes the menu onto a line of its own.
- The board's sideways scrollbar stays at the foot of the view, not under its tallest column, so a column reaching
  past the right edge, such as an unfolded Done, can be scrolled to and folded again.
- The list of unsaved moves above the board takes only the height it needs, at most a column's width, and scrolls
  beyond it; the board takes the rest instead of half.

## Plan

- `screen/lib/src/ui/planning_view.dart`: the folded card a `Row` with a fixed-width mark and the number in an
  `Expanded`; a `Scrollbar` on the horizontal controller around the vertical scroll view, reacting to depth 1; the
  unsaved moves' list no longer `Flexible`.
- `screen/lib/src/ui/actions.dart`: a dense menu button for the folded column.
- `screen/lib/src/ui/style.dart`: the mark's width and the dense button's size.
- No test: each is how the board reads and is placed, which the rules leave to a fix without a test.

## Acceptance

- With a real project of over a hundred stories, the folded Ideas column's rows stand even, an unfolded Done can be
  folded again, and the board fills the height while moves are unsaved.
