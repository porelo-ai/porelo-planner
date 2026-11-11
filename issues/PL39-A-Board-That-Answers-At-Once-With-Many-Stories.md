# PL39 — A Board That Answers At Once With Many Stories

```json
{
  "$schema": "issue-1.json",
  "id": "PL39",
  "title": "A Board That Answers At Once With Many Stories",
  "state": "Implementing",
  "size": "M",
  "priority": "Medium",
  "rank": 11,
  "blockedBy": [],
  "covers": "Folding a column, searching and every other change answers at once on a board of a few hundred stories, measured in profile mode before anything is optimized"
}
```

**Size:** M - one module, the screen, with a measurement in profile mode first and no contract change.

**What must be true.** On a board of a few hundred stories and their issues, folding a column, typing in the search
and every other change answers without a felt delay.

## Why

In the operator's test the board felt sluggish: folding Ideas and typing a search both lag. A widget test with 300
stories showed the board's own logic at a few milliseconds per change, while each change rebuilds about 4,000
elements - every card's buttons, texts, icons and drop targets. Building only the cards in sight, with each column
scrolling on its own, brought no felt gain and was taken back. The test ran in debug mode, which is several times
slower than the shipped build.

## Plan

- Measure first: the board with a few hundred stories in profile mode, with the Flutter DevTools' performance and CPU
  views, for a fold of a column, a keystroke in the search and a drag; the frame times and where the build time
  goes, per widget.
- Then change only what the measurement names, in `screen/lib/src/ui/planning_view.dart` and the board's model
  `screen/lib/src/planning_board.dart`: likely a narrower rebuild (a column or a card rebuilt alone instead of the
  whole board), and fewer widgets per card.
- The test that goes red first: a widget test with a few hundred stories that counts the elements rebuilt by a fold
  of one column, bounded to that column's.

## Acceptance

- The measured time of a fold and of a keystroke in profile mode, before and after, each under a frame's budget of
  16 ms or named with the reason it cannot be.
- The rebuild test fails on today's board and passes after.
