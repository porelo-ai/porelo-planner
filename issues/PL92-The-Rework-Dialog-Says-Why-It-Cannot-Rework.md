# PL92 — The Rework Dialog Says Why It Cannot Rework

```json
{
  "$schema": "issue-1.json",
  "id": "PL92",
  "title": "The Rework Dialog Says Why It Cannot Rework",
  "state": "Idea",
  "size": "-",
  "priority": "Medium",
  "rank": 1,
  "blockedBy": [],
  "covers": "The Rework dialog of a stopped reading says why Rework cannot be chosen when the Reworking rule names no agent, and the screen's fake planner names models on its rules and sends its rules with every board it pushes, as a planner does"
}
```

**What must be true.** A person in the Rework dialog of a stopped reading either can choose Rework or reads why not:
today a Reworking rule that names no `models` leaves the dialog's Rework disabled with no word. And the screen's fake
planner (`screen/lib/src/fake/mock_planning.dart`) behaves as a planner does: its rules name models, and a board it
pushes carries its rules, which the screen takes from a whole board (`PlanningBoard`, "A whole board brings its own
workflow"); today it pushes stories only, so changed rules never reach the screen in the window's tests.
