# PL123 — A Refused Start Reads As An Error

```json
{
  "$schema": "issue-1.json",
  "id": "PL123",
  "title": "A Refused Start Reads As An Error",
  "state": "Backlog",
  "size": "XS",
  "priority": "Medium",
  "rank": 59,
  "blockedBy": [],
  "covers": "A Start or another board action the window or the planner refuses is shown above the board in the theme's error colour with an error icon, as the screen's other failures are, so the person sees that nothing was created"
}
```

**Size:** XS - the lines above the board in `planning_view.dart`, one widget at most.

**Estimate:** 17 min, the measured median of XS.

**What must be true.** A person whose Start or Discuss the window refuses - a machine without a homeserver, among
others, where nothing was created - sees at once that it failed. In the window a refused Start stood as one plain line
under the board's header, and the person took the Discuss for lost.

## Plan

- **Today, read in the code.** `planning_view.dart` draws the board's `actionProblem` (key `planning-action-error`)
  in the error colour without an icon, and each of `workProblems` and `workUnsupported` as plain text; the provider's
  problem goes through `_problem`, also in the error colour without an icon. The window pinned a screen from before the
  action line took the error colour, so the line it showed was plain; on main a refused Start that lands in a story's
  work problem is still plain.
- **The change.** Every line above the board that says an action or a read failed - `actionProblem`, a story's work
  problem and the provider's problem - takes the error colour and a leading error icon from the theme, through one
  small widget.
- **Test.** None: a colour and an icon only read wrong, and each line's presence is held by the tests of its action.

## Acceptance

- A refused Start's line stands above the board in the error colour with an error icon; the screen's suite green.
