# PL116 — The Board Opens With Its Cards Shown

```json
{
  "$schema": "issue-1.json",
  "id": "PL116",
  "title": "The Board Opens With Its Cards Shown",
  "state": "Implementing",
  "size": "XS",
  "priority": "Medium",
  "rank": 52,
  "blockedBy": [],
  "covers": "A planning screen opened for the first time shows the cards of every state that holds stories, none folded to its count; a fold the person makes is kept afterwards"
}
```

**Size:** XS - the screen's starting folds in `PlanningPreferences` and one widget test.

**Estimate:** 17 min, the measured median of XS.

**What must be true.** A person who opens Planning for the first time sees the stories at once. Today the board can
open with Idea, Accepted, Analyzed, Estimated, Backlog and Review each folded to its count and a chevron, and only the
empty working states open; the person must unfold each one to see a card. After the first open, a state or column the
person folds stays folded as today.

## Plan

- **Today, read in the code.** `PlanningPreferences` (`screen/lib/src/planning_board.dart`) starts `foldedStates`
  empty and `foldedPhases` as `{'Ideas', 'Done'}`; `planning_view.dart` `_state` draws a folded state as its count and a
  chevron alone, and a state of a folded column as narrow tiles. The fold the person makes is kept in the board's
  preferences per project.
- **The change.** No state and no column holding open stories starts folded: `foldedPhases` starts as `{'Done'}`, so
  the Ideas column shows its cards as the others do, and Done, whose closed states show only when the person asks,
  keeps its narrow column.
- **Test, red first.** Widget, `planning_view_test.dart`: a first open of a board with stories in Idea, Backlog and
  Review shows each story's card, and a state the person folds stays folded after the board reloads. Harm: a person
  who sees an empty board and takes the project for having no work.

## Acceptance

- The widget test, seen red against today's starting folds; the screen's whole suite green.

## To be checked

- Why the operator's board showed states folded to their counts, which today's starting folds do not do: the screen
  ref the window pins, or a fold the window keeps between runs - asked of the window's agent.
