# PL39 — A Board That Answers At Once With Many Stories

```json
{
  "$schema": "issue-1.json",
  "id": "PL39",
  "title": "A Board That Answers At Once With Many Stories",
  "state": "Review",
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

## Measured

In profile mode on the operator's board, with the Flutter DevTools, folding a column three times and typing in the
search, the build time per frame:

| | Frames over 16.7 ms | 95 % of frames under | Slowest frame |
|---|---|---|---|
| Before | 13 % | 27 ms | 213 ms |
| Each card cached by its inputs | 38 % | 81 ms | 400 ms |
| And the columns sorted once per change | 6 % | 18 ms | 127 ms |

- Before, every change built all of the board's cards again, each with its action menu.
- With each card kept as last built, only the changed column's cards are built; but every slow frame still spent 50
  to 110 ms in garbage collection: each column was filtered and sorted several times a frame, with a regular
  expression made for every comparison and every title copied in lower case.
- With the columns sorted once per change, and each story's search words and number made once, the slow frames are
  the unfolding of a large column - about 70 ms for 125 cards - and a keystroke in the search at 21 to 28 ms.
- Not under budget, and why: the board scrolls as one page, so an unfolded column is built whole; building only the
  cards in sight needs each column to scroll by itself, a change of the layout, which is
  [PL56](README.md#pl56).
- The rebuild test, `planning_board_rebuild_test.dart`, failed on the board before and passes after; a test that the
  columns follow the search and the priority was seen red with either left out of what they are kept by.

## Reading

Read once by another model, asking whether a cached card or column can show stale data or keep a board alive: no
finding. Every input a card or a column is built from is in its key, mutable ones taken by value; a column cache holds
only the latest columns of its board, the card cache lives in the view of one board, and the per-story keys are held
weakly.
