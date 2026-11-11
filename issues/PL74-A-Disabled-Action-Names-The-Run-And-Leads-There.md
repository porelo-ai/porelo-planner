# PL74 — A Disabled Action Names The Run And Leads There

```json
{
  "$schema": "issue-1.json",
  "id": "PL74",
  "title": "A Disabled Action Names The Run And Leads There",
  "state": "Backlog",
  "size": "M",
  "priority": "High",
  "rank": 1,
  "blockedBy": [],
  "covers": "An action the board offers but cannot do now, such as Start work on a story whose run needs attention, names that run and what needs attention, and leads there in one step"
}
```

**Size:** M - the reasons of the board's unavailable actions, a way on from them and the story page's view of a run
that needs attention, in the screen package; a public type the window may render changes compatibly.

**Estimate:** 67 min, the measured median of M.

**What must be true.** An action the board offers but cannot do now says why in words the person can act on: "Start work..." on a story whose run needs attention names that run and what in it needs attention, and opens it, or the Needs-you entry it stands for, in one step, instead of only saying it is unavailable.

## Plan

Read in today's code: `PlanningBoard.startUnavailable` (`screen/lib/src/planning_board.dart`) answers "the run needs
attention" when the story's run ended, or an issue of it carries a reason other than `NONE`, `needsYou` or
`readingStopped` - without naming which issue or why. A `PlanningAction` (`screen/lib/src/ui/actions.dart`) carries
only an `unavailable` text, which the menu shows as a disabled item's subtitle. The story's page shows the run's state
and tasks (`planning_story_view.dart`) but not what needs attention; the window's *Needs you* draws each row with the
package's `StoryWorkAttentionView`.

Steps:

1. `startUnavailable` names what needs attention: each such issue with its reason in words (round limit reached,
   failed, not pushed, reading stopped, ...) or the run's end, from the same data it reads today.
2. `PlanningAction` gains an optional way on - a label and what it runs - shown beside an unavailable action's reason
   in the card's menu and on the story page; a Start work held by attention leads to the story's page.
3. The story's page shows the run's attention for that story with the package's own `StoryWorkAttentionView`, so the
   person reads and settles it there, as in *Needs you*.
4. Red first, unit in `planning_board_test.dart`: a story whose issue's reading stopped answers that issue and its
   reason (harm: the person cannot find what holds the work); a widget test that the way on opens the story's page and
   that page shows the attention row.

## Acceptance

- Start work on a story whose run needs attention names the issue and the reason, and one step opens the story's page
  where that attention is shown and can be settled. Seen failing first: today's "the run needs attention".

## To be checked

- How the window's command palette renders a `PlanningAction`, so the added way on shows there too or is left out
  there knowingly (asked of the window's agent).
