# PL136 — The Board Opens At Once

```json
{
  "$schema": "issue-1.json",
  "id": "PL136",
  "title": "The Board Opens At Once",
  "state": "Backlog",
  "size": "L",
  "priority": "Highest",
  "rank": 10,
  "blockedBy": [],
  "covers": "A board of hundreds of stories opens, reopens and folds a lane at once: each phase builds only the cards in its view and scrolls on its own, a fold rebuilds no card out of view, and a loading mark stands while the planner answers"
}
```

**Size:** L - the board's whole layout changes from one scroll over all cards to a lazily built scroll per phase, with
the drop targets, the search's scrolling and the remembered offsets; one module, but a design to settle first.

**What must be true.** Planning opens on a board of 360 stories about as fast as on one of 35, the second time too,
and folding a lane does not hold the window.

## Plan

- Measured today (screen at f8465da, debug, headless, 360 stories with 4 issues each): open 1.7 to 2.1 s, fold and
  unfold 0.6 to 0.8 s, against 0.38 s and 0.14 s for 35 stories. `screen/lib/src/ui/planning_view.dart` builds and
  lays out every card at once: one vertical `SingleChildScrollView` around a horizontal one around a `Row` of every
  phase, each phase a `Column` of its states, each state a `Column` of all its cards; a fold reruns the whole board.
  The card cache lives in the board's view state, which goes when the person leaves Planning. The sorted columns are
  already kept by `PlanningBoard.cards`.
- The board: the horizontal scroll keeps a `Row` of phases, and each phase is a `CustomScrollView` of its own, bounded
  by the board's height: its head, then per state a `SliverMainAxisGroup` with the state's head and a
  `SliverList.builder` of its cards (the folded phase's tiles the same way), so only cards in view are built. Each
  state's drop target covers its head and each of its cards, and an empty state keeps a drop box.
- The phases' scroll offsets are remembered in `PlanningPreferences` by phase name, as the one offset is today.
- The search brings the first found card into view by scrolling its phase to the card's index, then
  `Scrollable.ensureVisible` once it is built; a search that finds the same first card again does not scroll.
- A loading mark (a progress indicator) beside "Waiting for the planner's answer." and on the page shown before any
  planning was read.
- Red first: a widget test that times opening, leaving, returning and folding a 360-story board against a 35-story
  one in the same run, with the bounds the window's guard uses (open under 3 times, fold under 2.75 times); and that
  a card far down a phase is not built until it is scrolled to, while the search still brings it into view.

## Acceptance

- The timing test passes and was seen red against today's board; the screen's suite passes; the window's large
  board guard passes once its pin takes this commit.
