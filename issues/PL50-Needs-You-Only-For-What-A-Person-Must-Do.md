# PL50 — Needs You Only For What A Person Must Do

```json
{
  "$schema": "issue-1.json",
  "id": "PL50",
  "title": "Needs You Only For What A Person Must Do",
  "state": "Estimated",
  "size": "L",
  "priority": "High",
  "rank": 25,
  "blockedBy": [
    "PL46",
    "PL64"
  ],
  "covers": "The planner marks an issue as needing the person only where they must act, and says what to do; an issue in a working state with no run of this planner is shown as such, not raised; a person can put a raised issue away until it changes",
  "openQuestions": 1
}
```

**Size:** L - three steps across the board's contract, the work record's file, the app and the screen package: a
contract change, a stored format change and a new request.

**Estimate:** 113 min, the measured median of L. Uncertain: where a row put away is kept (the open question) may move
its third step into the window, which makes it smaller here.

**What must be true.** Every row the planner raises in *Needs you* asks the person for something they can do, and says
what; what is only information is shown on the board, not raised; and a person can put a row away.

## Plan

Read in today's code: `WorkAnswers.issue` answers `needsYou` from the story run's `NextStep.Progress` and, when there
is none, from `WorkAnswers.lost` - true for every issue in a working state whose story has no run in this planner's
work record. Work set by commits or started outside the planner has no run here, so every issue in Implementing on
such a project is raised, with no rounds (the row shows "reading ? of ?"). The run's reasons stand in
`Progress.reason` and the run's `PlanWorkIssue.reason`: `ROUND_LIMIT`, `FAILED`, `UNPUSHED`, `INVALID_FINDINGS`,
`STOPPED`, `SAVE_REFUSED`, `NEXT_STEP_UNAVAILABLE`; the board's `Issue` carries `needsYou` and `readingStopped` but no
reason. The window lists the screen package's `PlanningBoards.needsYou` (`screen/lib/src/planning_board.dart`) in its
*Needs you* and draws every row with the package's `StoryWorkAttentionView`; it already lets a person mark a story's
ended run as seen, kept in the window alone.

[PL64](README.md#pl64) gives the rows of a failed task (open its result) and of a blocking reading (`BLOCKING_FINDINGS`
and `ROUND_LIMIT`, open the Reworking dialog) their action; this issue gives every other reason its own. With the
worker field of [PL46](README.md#pl46) and the work that obeys it ([PL65](README.md#pl65)), a Manual issue in a
working state is worked by hand and has no run by design, so it is never counted as worked outside this planner.

1. **No run here is information** (S). `lost` no longer sets `needsYou`; the board's `Issue` gets `noRunHere`
   (boolean, required), true for an Agent issue in a working state with no run in this planner's record, and the
   screen shows it on the issue's row as "worked outside this planner". Contract change in `planner.yaml`,
   `Answers.Issue`, `models.dart`, the mock board. Red first, unit, in a new `WorkAnswersTest` in `app`: an issue in
   Implementing with no run answers `needsYou` false and `noRunHere` true; a Manual one answers both false.
2. **Each raised row names its action** (S). The board's `Issue` gets `attention` - the run's reason, `NONE` when not
   raised - and `StoryWorkAttentionView` carries an action for each reason [PL64](README.md#pl64) leaves:
   `NEXT_STEP_UNAVAILABLE` and `SAVE_REFUSED` look at the failure and start again or stop; `STOPPED` move the issue on
   or start again; `UNPUSHED` open the task to push its commits; `INVALID_FINDINGS` read again or go to Review. Red
   first, widget test of the screen package: a `STOPPED` row offers its two actions.
3. **A raised row can be put away** (M). A new request `POST .../issues/{issue}/attention/dismissed` with the reason
   and state shown; `WorkRecord` keeps the dismissal per issue with them, and `WorkAnswers` answers `needsYou` false
   while both are unchanged. The screen package gets the action on every row. Red first, integration in `app`: a
   dismissed `ROUND_LIMIT` issue answers `needsYou` false; the same issue after a new reading answers true again; a
   dismissal naming another state is refused.

Order: 1, 2, 3, after [PL64](README.md#pl64). Sum by the medians: 2 S + 1 M = 33 + 33 + 67 = 133 minutes, an L.

## Acceptance

- *Needs you* holds only issues with a reason and an action; dismissing one keeps it away until its state or reason
  changes; an issue with no run here shows on the board and is not raised, and a Manual issue is neither.

## Open questions

- Is a row put away kept by the planner, for every window of the account and across restarts (step 3), or by the
  window alone, as a seen story end is kept today, which would move step 3 into the window?
