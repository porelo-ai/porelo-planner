# PL117 — A Refused Action Is Said In The Error Colour

```json
{
  "$schema": "issue-1.json",
  "id": "PL117",
  "title": "A Refused Action Is Said In The Error Colour",
  "state": "Implementing",
  "size": "XS",
  "priority": "High",
  "rank": 53,
  "blockedBy": [],
  "covers": "A board action the window or the planner refuses - a discussion Porelo would not start, among others - is said above the board in the error colour, as a provider's problem is, so the person sees that nothing happened"
}
```

**Size:** XS - one style in `planning_view.dart`.

**Estimate:** 17 min, the measured median of XS.

**What must be true.** When an action of the board is refused - a discussion whose start Porelo refuses, a new issue the
planner refuses - the person sees at once that it failed. Today the refusal stands above the board as one line of plain
text, so a person who pressed Discuss and saw no task takes it for nothing having happened.

## Plan

- **Today, read in the code.** `planning_view.dart` draws a provider's problem in `colorScheme.error` and the board's
  `actionProblem` (key `planning-action-error`) in the default text style.
- **The change.** `actionProblem` takes the same error style as the provider's problems.
- **Test.** None: a colour only reads wrong, and the line's presence is already held by the discussion's and the new
  issue's tests.

## Acceptance

- A refused discussion's line stands in the error colour above the board; the screen's suite green.
