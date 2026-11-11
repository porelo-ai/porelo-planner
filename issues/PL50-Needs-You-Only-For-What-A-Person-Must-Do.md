# PL50 — Needs You Only For What A Person Must Do

```json
{
  "$schema": "issue-1.json",
  "id": "PL50",
  "title": "Needs You Only For What A Person Must Do",
  "state": "Analyzed",
  "size": "L",
  "priority": "High",
  "rank": 25,
  "blockedBy": [],
  "covers": "The planner marks an issue as needing the person only where they must act, and says what to do; an issue in a working state with no run of this planner is shown as such, not raised; a person can put a raised issue away until it changes"
}
```

**Size:** L - three steps across the board's contract, the work record's file, the app and the screen package: a
contract change, a stored format change and a new request.

**What must be true.** Every row the planner raises in *Needs you* asks the person for something they can do, and says
what; what is only information is shown on the board, not raised; and a person can put a row away.

## Plan

Read in today's code: `WorkAnswers.issue` answers `needsYou` from the story run's `NextStep.Progress` and, when there
is none, from `WorkAnswers.lost` - true for every issue in a working state whose story has no run in this planner's
work record. Work set by commits or started outside the planner has no run here, so every issue in Implementing on
such a project is raised, with no rounds (the window shows "reading ? of ?"). The run's own reasons stand in
`Progress.reason`: `ROUND_LIMIT`, `NEXT_STEP_UNAVAILABLE`, `STOPPED`, `SAVE_REFUSED`; the screen package lists the
raised issues in `PlanningBoards.needsYou` (`screen/lib/src/planning_board.dart`).

1. **No run here is information** (S). `lost` no longer sets `needsYou`; the board's `Issue` gets `noRunHere`
   (boolean, required), true for an issue in a working state with no run in this planner's record, and the screen
   shows it on the issue's row as "worked outside this planner". Contract change in `planner.yaml`, `Answers.Issue`,
   `models.dart`. Red first in `app`, in a new `WorkAnswersTest`: an issue in Implementing with no run answers
   `needsYou` false and `noRunHere` true.
2. **Each raised row names its action** (S). The board's `Issue` gets `attention` - the run's reason, `NONE` when
   not raised - and `PlanningNeedsYou` carries an action per reason: `ROUND_LIMIT` choose rework, Review or stop;
   `NEXT_STEP_UNAVAILABLE` and `SAVE_REFUSED` look at the failure and start again or stop; `STOPPED` move the issue
   on or start again. Red first in the screen package: a `ROUND_LIMIT` row offers the three choices.
3. **A raised row can be put away** (M). A new request `POST .../issues/{issue}/attention/dismissed` with the reason
   and state shown; `WorkRecord` keeps the dismissal per issue with them, and `WorkAnswers` answers `needsYou` false
   while both are unchanged. The screen package gets the action on every row. Red first in `app`: a dismissed
   `ROUND_LIMIT` issue answers `needsYou` false; the same issue after a new reading answers true again; a dismissal
   naming another state is refused.

Order: 1, 2, 3. Sum by the medians: 2 S + 1 M = 33 + 33 + 67 = 133 minutes, an L.

## Acceptance

- *Needs you* holds only issues with a reason and an action; dismissing one keeps it away until its state or reason
  changes; an issue with no run here shows on the board and is not raised.

## To be checked

- Whether the window lists `PlanningBoards.needsYou` itself or through the screen package's view, so the dismiss
  action is placed where the row is drawn.
