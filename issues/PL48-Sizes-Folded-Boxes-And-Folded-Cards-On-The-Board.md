# PL48 — Sizes, Folded Boxes And Folded Cards On The Board

```json
{
  "$schema": "issue-1.json",
  "id": "PL48",
  "title": "Sizes, Folded Boxes And Folded Cards On The Board",
  "state": "Review",
  "size": "M",
  "priority": "High",
  "rank": 31,
  "blockedBy": ["PL47"],
  "covers": "Every story card and issue row shows its size as a badge beside its priority; a state's box folds to its heading with name and count, its content hidden and the column as wide as before; a single story card folds to its heading with number and priority"
}
```

**Size:** M - three changes in the screen package's board view and its preferences, each with its widget test, and
the story's size read from a member the board's answer gains first; no contract of its own.

**Estimate:** 67 min, the measured median of M. Uncertain: whether a size badge fits the 136-pixel tiles of a narrowed
column beside mark, number and menu.

**What must be true.** A person sees each story's and issue's size as a badge beside its priority, and can fold away
what they do not need: a state's box down to its heading, and a single story card down to its number and priority,
without the column changing its width. Every box starts open, `In analysis` included; a person folds it.

## Plan

Everything is in `screen/`; the planner's Java side is untouched. Blocked by [PL47](README.md#pl47), which gives the
board's `Story` its `size` in `planner.yaml`; until then a story has no size to show.

- **The size badge.** Today `_buildCard` in `screen/lib/src/ui/planning_view.dart` shows a story's number, the
  `_priority` chip and the menu, no size; an issue row inside an open card shows `issue.size` as plain text after
  `issue.state`; the compact tile of a narrowed column (the `folded` branch of `_state`) shows the priority mark,
  number and menu. `PlanStory` in `screen/lib/src/models.dart` has no `size`; `PlanIssue.size` is read with
  `_planText` and is empty when unsaid. Steps: `PlanStory.size` read as `PlanIssue.size` is (empty when absent, and
  carried by `PlanStory.withMoves`); a `_size` badge styled as `_priority`, shown on the story card beside the
  priority chip, on the issue row in place of the plain text (an issue carries no priority; the badge stands beside
  its state), and on the compact tile beside the mark. An empty size or `-` shows no badge.
- **A folded box.** Today `foldState` (`PlanningBoard` in `screen/lib/src/planning_board.dart`) toggles
  `PlanningPreferences.foldedStates`, and `_state` (`planning_view.dart`, `phaseFolded ||
  foldedStates.contains(...)`) draws a state folded by its own chevron as compact tiles, the same as a narrowed
  column - the box's "v" narrows its tiles instead of folding the box; the column's width already
  depends only on `foldedPhases` (`_phase`). Change: a state folded by its own chevron shows its heading row only
  (name, count, chevron) and no tile; a state inside a folded phase keeps the compact tiles, so only the column's
  chevron narrows the column and shows tiles. The box stays a `DragTarget`, so a story can still be dropped on it.
  `PlanningPreferences.foldedStates` starts empty: `In analysis`, folded by default today, starts open like every
  other box.
- **A folded card.** `PlanningPreferences` gains `foldedStories` and `PlanningBoard` a `foldStory(id)`, as
  `expandedStories` and `expand`. The card's top row gets a fold button (`planning-fold-story <id>`); folded, the card
  shows number, priority, size, menu and a pending move or its refusal, and hides title, work line, reading line,
  issue list and progress bar. The fold flag joins the inputs of `_card`'s cache, or a folded card is served stale.
- **What a person or a server could do that the above does not forbid, and what forbids it.**
  - A fold must not hide that a story needs the person: a folded card keeps its "needs you" mark, and a folded box's
    heading shows a "needs you" mark with the number of its cards that raise one (`planning-state-raised <state>`).
    What a task is doing stays on the open card; a folded one shows only that it needs the person.
  - A size is shown only as the contract gives it, `XS` to `XL`: any other text from a server shows a `?` badge read
    as "Size not understood", never the text itself, and empty or `-` shows none.
  - A fold names only a story the board shows: `foldStory` ignores an id the board does not hold, and an accepted
    board drops the folds of stories it no longer holds, so a story saved anew under an old id starts open and the
    set stays bounded by the board.
  - Declined: folds and the other choices stay per project, kept while the project's board is gone, as
    `expandedStories` and `foldedStates` already are, so a person's choices survive a reconnect; their number is
    bounded by the projects a person opened. A board moved to another project by `changeProject` drops its story
    folds, since story ids repeat across projects.
- **The code reading's findings.** A stopped reading was left out of what a fold shows: the folded card, the folded
  box's count and the narrowed tile now mark every need of the person, a stopped reading included. A folded box hid a
  staged move and its refusal: its heading now says how many of its stories were moved and not saved, and each
  refusal's words. Declined: a size in another case or with spaces (`m`, `M `) shows as not understood, since the
  contract gives `XS` to `XL` exactly.
- **Not here.** The story's own page (`planning_story_view.dart`) keeps its priority chip without a size; the story's
  test names the board's cards and tiles only.
- Tests, all widget tests in `screen/test` over `FakePlanningSource` (`screen/test/support/fake_planning.dart`),
  red first:
  - Folding a box finds none of its cards and the phase at `PlanningStyle.column` width; harm: a fold that hides
    nothing or narrows the column, so work in that state is lost from sight or the board reflows.
  - Folding one card finds its number and not its title, and the card beside it with its title; harm: a fold that
    reaches other cards hides their work. A second assertion that the folded card's own rebuild returns the folded
    card guards the cache.
  - A story and an issue of size M each show an M badge; a size `-` shows none. Harm: a wrong size is planned on.
  - `planning_board_rebuild_test.dart` keeps passing: folding a box builds no card of another state.

## Acceptance

- A folded box shows its heading only, at the column's width; a narrowed column still shows its compact tiles; on a
  fresh board every box is open, `In analysis` included.
- A folded card shows its number, priority and size only, and the cards beside it stay open.
- Every story card, issue row and compact tile with a size shows it as a badge; each new test has been seen red.
- The screen module's Maven build passes locally: `dart format`, `dart analyze --fatal-infos` and `flutter test`.

A size badge fits the narrowed column's tile beside the mark, a number as long as the mock board's longest and the dense
menu: an `XL` badge there lays out without overflow. No test in `screen/test` and none of the window's planning tests expects `In analysis` folded by default; the
window's tests use the keys `planning-fold-state <state>` and `planning-fold-phase <phase>`, which stay.
