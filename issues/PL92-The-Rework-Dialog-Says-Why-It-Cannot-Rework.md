# PL92 — The Rework Dialog Says Why It Cannot Rework

```json
{
  "$schema": "issue-1.json",
  "id": "PL92",
  "title": "The Rework Dialog Says Why It Cannot Rework",
  "state": "Implementing",
  "size": "S",
  "priority": "Highest",
  "rank": 2,
  "blockedBy": [],
  "covers": "The Rework dialog of a stopped reading says why Rework cannot be chosen when the Reworking rule names no agent, and the screen's fake planner names models on its rules and sends its rules with every board it pushes, as a planner does"
}
```

**Size:** S - the screen package alone: one dialog and the fake planner, with their widget tests; no contract.

**Estimate:** 33 min, the measured median of S.

**What must be true.** A person in the Rework dialog of a stopped reading either can choose Rework or reads why not:
today a Reworking rule that names no `models` leaves the dialog's Rework disabled with no word. And the screen's fake
planner (`screen/lib/src/fake/mock_planning.dart`) behaves as a planner does: its rules name models, and a board it
pushes carries its rules, which the screen takes from a whole board (`PlanningBoard`, "A whole board brings its own
workflow"); today it pushes stories only, so changed rules never reach the screen in the window's tests.

## Plan

- **The dialog.** `_ReworkingDialog` in `screen/lib/src/ui/story_work_attention.dart` builds its choice of agent from
  the Reworking rule's models; with none, its rework choice stays null and Rework stays disabled with no word. Change:
  a rule that names no agent shows the reason in the dialog ("The Reworking rule names no agent; name one under
  `models` in the project's work rules") and keeps Rework disabled; Review and Stop stay available.
- **The fake planner.** `screen/lib/src/fake/mock_planning.dart` names models on every work rule it serves, as the
  planner's mock board does, and a board it pushes carries its states and rules, so the screen takes changed rules from
  a whole board as it does from the planner.
- **Tests, widget, red first.** A stopped reading whose Reworking rule names no models shows the reason and keeps Rework
  disabled; with models, Rework enables. A rule changed in a board the fake pushes reaches the screen. Harm: a person
  left in a dialog that cannot do what they came for, and window tests passing over rules the planner never serves.

## Acceptance

- The dialog says why Rework is unavailable without an agent and enables it with one; the fake's pushed rules reach
  the screen; each new test seen red; the screen's Maven build passes.
