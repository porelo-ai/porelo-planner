# PL56 — The Board Builds Only What Is Seen

```json
{
  "$schema": "issue-1.json",
  "id": "PL56",
  "title": "The Board Builds Only What Is Seen",
  "state": "Estimated",
  "size": "L",
  "priority": "Medium",
  "rank": 29,
  "blockedBy": [],
  "covers": "The board builds only the cards in sight, so unfolding a column of hundreds of stories and every keystroke in the search answer within a frame; its speed is measured headless by the agent in profile mode, not by hand"
}
```

**Size:** L - the board's layout changes to one lazy list per column in the screen package, and a measuring example
app with a Linux runner and a headless profile run is new.

**Estimate:** 113 min, the measured median of L. Uncertain: the first Linux desktop build and headless profile run of
the example app have never run in this repository, and existing widget tests may find cards that are no longer built.

**What must be true.** On a board of several hundred stories, unfolding a column and typing in the search answer
without a felt delay, and every change to the board's screen can be measured for speed without a person at the
desktop.

## Why

The board scrolls as one page with all its columns in it, so a column is laid out whole: unfolding one of 125 cards
builds all 125, about 70 ms in profile mode, and a keystroke in the search still takes 21 to 28 ms, a third of it
garbage collection. Only cards in sight can be built lazily once each column scrolls by itself. It builds on the board's
folded boxes and folded cards, so the layout it changes is the one that stays.

## Plan

- **Today**, in `screen/lib/src/ui/planning_view.dart`, `_BoardViewState.build` nests a vertical
  `SingleChildScrollView` (`planning-board-scroll`, `_vertical`) around a horizontal one (`_horizontal`) around a
  `Row` of `_phase` columns; `_phase` is a `Column` of `_state` boxes, each a `DragTarget<String>` around a `Column`
  holding every card (`_card`) or, folded, a `Wrap` of every compact tile. So every card of every column is built and
  laid out. `_card` already reuses a card whose inputs did not change (`_cards`), and `PlanningBoard.cards` in
  `planning_board.dart` sorts once per change; both stay. `PlanningPreferences` keeps one `verticalOffset`.
- **The layout.** The horizontal scroll view stays the outer one, with the sideways bar at the view's foot. Each
  phase column is a `CustomScrollView` of the view's height with its own controller: per state a heading and a
  `SliverList.builder` over `board.cards(state)` (a folded box its heading alone, as the board folds
  it), the "Stories outside the reported workflow" column likewise. A `DragTarget` cannot wrap a sliver, so the
  heading and each card of a state are each a target for that state, sharing one hover flag per state for the
  "Drop here for" hint. `verticalOffset` becomes one offset per column name, kept as today.
- **The measuring app.** `screen/example/`, its own pubspec with a path dependency on the package and
  `integration_test` and `flutter_driver` from the SDK, with the Linux runner `flutter create --platforms=linux`
  generates (C++ and CMake the platform requires; its dot files not committed). It shows a board of a few hundred
  stories from `MockPlanningSource`; `integration_test/board_speed_test.dart` unfolds a column and types in the search
  inside `traceAction`, and `test_driver/board_speed.dart` writes the timeline summary. One script runs it headless:
  `xvfb-run -a -s '-screen 0 1280x800x24' dbus-run-session -- flutter drive --profile -d linux`, with DISPLAY and
  WAYLAND_DISPLAY unset, the board's font bundled and a private temporary directory. Under Xvfb the raster times
  are software rendering, so only build times and runs against each other count. It does not run in the Build
  workflow: a runner's speed varies, so a time budget there would fail at random.
- **Tests.** Widget test (unit level, seconds), red first: on a board of 300 stories in a view of 1280 by 800,
  unfolding a phase column builds no more cards than fit in the view, counted with `debugOnRebuildDirtyWidget` as
  `planning_board_rebuild_test.dart` counts; today it builds every card of the column. A second: a keystroke in the
  search builds only cards in sight. A third: a card dropped on a state's heading or on a card of another state
  moves the story there, as the box did. Harm prevented: a board that freezes on every keystroke and fold, and a drop
  that stops landing once the box is no longer one target. The headless run is the measurement, not a test of the
  suite; its first run on today's board shows an unfold's worst build time over 16 ms.
- A changelog entry under `changelog/unreleased/`, since the screen ships in the window.

## Acceptance

- The widget tests above are green, and each was seen red with the board laid out as today.
- The headless summary shows an unfold and a keystroke each under 16 ms of build time, against over 16 ms for the
  unfold on today's board; the run is one command an agent starts.
- The whole screen suite is green under `./mvnw -B -s settings.xml -Pall -pl screen verify`.

## To be checked

- Which existing widget tests find a card below the default 800 by 600 test view and now need a larger view or a
  scroll to it.
- Whether `dart analyze --fatal-infos` at the package root reads `example/`, and so needs its `pub get` in the
  Maven build, or `example/` excluded in `analysis_options.yaml`.
- Which font the window bundles for the board, so the example app bundles the same one, and its license.
- Whether dragging a card toward a state below the column's sight needs the column to scroll while dragging.
- That `SliverMainAxisGroup` and `DecoratedSliver` in Flutter 3.47.0 carry a state's box and its working color.
