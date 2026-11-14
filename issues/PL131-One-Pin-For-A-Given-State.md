# PL131 — One Pin For A Given State

```json
{
  "$schema": "issue-1.json",
  "id": "PL131",
  "title": "One Pin For A Given State",
  "state": "Backlog",
  "size": "XS",
  "priority": "Highest",
  "rank": 5,
  "blockedBy": [],
  "covers": "A story whose state was given shows one mark, the filled pin, whether its issues fit that state or not; its tooltip still names the issues that do not fit, and no warning colour is added"
}
```

**Size:** XS - one widget of the screen package and its test; no contract.

**What must be true.** The person sees at a glance which stories hold a state of their own, with one mark, and reads
in its tooltip which issues do not fit.

## Plan

- `planningGivenMark` in `screen/lib/src/ui/planning_detail_widgets.dart` shows `Icons.push_pin_outlined` when the
  issues fit and `Icons.push_pin` when they do not. It shows `Icons.push_pin` for every given state; the tooltip's three
  messages stay.
- No red test: which icon stands is a look, not a harm; `planning_given_state_test.dart` keeps guarding the tooltip.

## Acceptance

- The screen's suite passes; a given state whose issues fit shows the filled pin.
