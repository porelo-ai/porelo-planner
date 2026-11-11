# PL50 — Needs You Only For What A Person Must Do

```json
{
  "$schema": "issue-1.json",
  "id": "PL50",
  "title": "Needs You Only For What A Person Must Do",
  "state": "Backlog",
  "size": "M",
  "priority": "High",
  "rank": 25,
  "blockedBy": [
    "PL46",
    "PL64"
  ],
  "covers": "The planner marks an issue as needing the person only where they must act, and says what to do; an issue in a working state with no run of this planner is shown as such, not raised; a person can put a raised issue away until it changes"
}
```

**Size:** M - one contract change across the app and the screen package, and an action on every row; a row put away
is kept by the window, not here.

**Estimate:** 67 min, the measured median of M. Uncertain: the window's own part, keeping a row put away, is built in
the window's repository and must land with the screen package's action.

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
3. **A raised row can be put away** (XS). A row put away is kept by the window alone, as a seen story end is kept
   today: it keeps the issue with the reason and state shown, and shows the row again once either changes. The
   screen package gives `StoryWorkAttentionView` a put-away action that hands the issue, its reason and its state to
   a callback of the window's, and `PlanningBoards.needsYou` leaves out what the window names as put away. Nothing
   is stored in the planner and no request is added. Red first, widget test of the screen package: a row put away is
   left out while its reason and state are unchanged and shown again when the reason changes.

Order: 1, 2, 3, after [PL64](README.md#pl64). Sum by the medians: 2 S + 1 XS = 33 + 33 + 15 = 81 minutes, an M.

## Acceptance

- *Needs you* holds only issues with a reason and an action; putting one away keeps it away in that window until its
  state or reason changes; an issue with no run here shows on the board and is not raised, and a Manual issue is
  neither.
