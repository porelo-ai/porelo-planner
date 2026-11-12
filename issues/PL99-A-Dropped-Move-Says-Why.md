# PL99 — A Dropped Move Says Why

```json
{
  "$schema": "issue-1.json",
  "id": "PL99",
  "title": "A Dropped Move Says Why",
  "state": "Backlog",
  "size": "S",
  "priority": "Highest",
  "rank": 6,
  "blockedBy": [],
  "covers": "A move a person chose on a story's page that the screen can no longer stage - its issue changed state or a save began while the menu stood open - is not dropped in silence: the page says why and the person chooses again"
}
```

**Size:** S - one class of the screen package and its widget tests, no contract.

**Estimate:** 33 min, the measured median of S.

**What must be true.** When a person chooses a new state for an issue on a story's page and the screen cannot stage
that move, the page says why, beside the issue, and nothing is staged. Today the choice is dropped without a word when
the issue list was read again while the menu stood open and the chosen state is no longer one the issue can move to,
or when a save began in the meantime. The person sees no change and no reason. While tasks move issues in the
background, this happens in normal use.

## Plan

- **Today, read in the code.** `PlanningDetail.queueMove` (`screen/lib/src/planning_detail.dart`) returns without
  a word when `moveUnavailable` is set (a save began, the detail is loading or not fully read), when the issue is gone
  from `issues`, or when `to` is neither its state nor among `moveStatesOf(issue)`. The menu in
  `planning_story_view.dart` is built from the issue as it stood when the menu opened.
- **The change.** `queueMove` sets a problem for that issue instead of returning silently: the issue is gone, it
  now stands in `<state>` and cannot move to `<to>` from there, or the reason `moveUnavailable` gives. The story page
  shows it beside the issue, as `moveProblem` is shown today, and it clears on the next choice. Nothing is staged.
- **Tests, red first, widget level (`screen/test`):** a menu opened, the issue list read again with the issue in
  another state, then the old choice selected: the page names the issue's new state and nothing is pending. The same
  with a save begun before the choice is made. Harm: a person's move lost without a word while the board looks as if
  nothing was asked.

## Acceptance

- Both dropped paths show their reason beside the issue and stage nothing; a valid choice stages as today; each new
  test seen red; the screen's tests and the Maven screen step pass.
