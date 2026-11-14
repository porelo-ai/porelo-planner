# PL136 — The Board Opens At Once

```json
{
  "$schema": "issue-1.json",
  "id": "PL136",
  "title": "The Board Opens At Once",
  "state": "Review",
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
- A loading mark beside "Waiting for the planner's answer.": a still hourglass, since a waiting page must have no
  endless animation (a guard holds it). The page shown when no planning has been read keeps its sentence alone, since
  it also stands when no planner answers at all.
- The card widget cache stays in the board's view state, since its cards hold the view's own callbacks; with lazy
  lists a return builds only the cards in view, and the sorted columns already live in `PlanningBoard`.
- A state's key `planning-state <name>` stands over its whole sliver group, heading and cards, so a card is still
  found within its state; the heading carries `planning-drop <name>`.
- Red first: a widget test that times opening, leaving, returning and folding a 360-story board against a 35-story
  one in the same run, with the bounds the window's guard uses (open under 3 times, fold under 2.75 times); and that
  a card far down a phase is not built until it is scrolled to, while the search still brings it into view.

- Taken from the plan's reading:
  - The search steps a phase toward the card by the built cards' mean height, frame by frame, until the card is built,
    and skips folded states; a folded phase's tile stands for its story there as before.
  - The drop hint takes the place of the state's name in its heading, so nothing under the pointer moves; a test
    seen red holds it.
  - Each card's drop target wraps it outside the card cache; the cache also reads whether any move is pending and the
    board's revision.
  - The stories outside the workflow have a lazy column of their own, and the search scrolls it too; a test seen red
    holds it.
  - A folded phase does not record its clamped offset over the open phase's.
  - The sideways scrollbar listens to the horizontal scroll alone, now at depth 0.
  - The loading mark is still, and stands only beside "Waiting for the planner's answer.".
- Read in the code's reading:
  - Fixed with a test seen red: two phases of one name, or a nameless phase beside the stories outside the workflow,
    shared one scroll and one key and crashed the board; each column now has a scroll of its own and a later phase of
    a repeated name a key with its index.
  - Fixed with a test seen red: the search measured from the top of the whole board, not the phase's view, and
    stopped once the card was built though not in view; it now steps from the nearest built card on the card's side
    until the card stands in view, a card taller than the view once its top does.
  - Fixed, held by the same test's narrowed search but not seen red alone: a newer search stops an older one's steps,
    and the found card counts as shown only once it stands in view.
  - Fixed without a test, since they only read wrong: a folded phase scrolls on a scroll of its own, and a column
    built again starts where it was left; a drag's end clears the drop hint.
- Measured after the change (debug, headless, after a warm-up run): the 360-story board opens, returns and folds in
  0.87 to 0.98 times the 35-story board's time; the board before the change took 7.8 to 9.8 times and fails the test.

## Acceptance

- The timing test passes and was seen red against today's board; the screen's suite passes; the window's large
  board guard passes once its pin takes this commit.
