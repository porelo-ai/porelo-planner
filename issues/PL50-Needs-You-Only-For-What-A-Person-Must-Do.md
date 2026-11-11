# PL50 — Needs You Only For What A Person Must Do

```json
{
  "$schema": "issue-1.json",
  "id": "PL50",
  "title": "Needs You Only For What A Person Must Do",
  "state": "Review",
  "size": "M",
  "priority": "High",
  "rank": 25,
  "blockedBy": [
    "PL64"
  ],
  "covers": "The planner marks an issue as needing the person only from its run, naming the run's reason; an issue in a working state with no run of this planner is shown as such, not raised; a person can put a raised issue away until it changes"
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
issue's worker field and the work that obeys it ([PL65](README.md#pl65)), a Manual issue in a
working state is worked by hand and has no run by design, so it is never counted as worked outside this planner.

1. **No run here is information** (S). `lost` no longer sets `needsYou`; the board's `Issue` gets `noRunHere`
   (boolean, required), true for an Agent issue in a working state with no run in this planner's record, and the
   screen shows it on the issue's row as "worked outside this planner". Contract change in `planner.yaml`,
   `Answers.Issue`, `models.dart`, the mock board. Red first, unit, in a new `WorkAnswersTest` in `app`: an issue in
   Implementing with no run answers `needsYou` false and `noRunHere` true; a Manual one answers both false.
2. **Each raised row names its reason.** The board's `Issue` gets `attention` - the run's reason, `NONE` when not
   raised (`WorkAnswers`, `Answers.Issue`, `planner.yaml`, `models.dart`, the mock board). An action for each reason
   is left to [PL77](README.md#pl77): today a raised row holds its story's Start, a halted run takes no start, the
   screen offers Stop only while work runs, and the row has no start hooks. Red first, unit, `WorkAnswersTest`: a
   raised issue names its run's reason and an unraised one `NONE`.
3. **A raised row can be put away** (XS). A row put away is kept by the window alone, as a seen story end is kept
   today: it keeps the issue with the reason and state shown, and shows the row again once either changes. The
   screen package gives `StoryWorkAttentionView` a put-away action that hands the issue, its reason and its state to
   a callback of the window's, and `PlanningBoards.needsYou` leaves out what the window names as put away. Nothing
   is stored in the planner and no request is added. A row put away whose issue is no longer raised is forgotten,
   so the same reason raised anew shows again. Red first, widget test of the screen package: a row put away is left
   out while its reason and state are unchanged and shown again when the reason or the state changes, or when it
   settles and is raised anew.

Order: 1, 2, 3, after [PL64](README.md#pl64). Built: `WorkAnswersTest` (no run here not raised; a Manual issue
neither raised nor worked outside; the reason named) and `needs_you_put_away_test.dart` (a row put away returns when
its reason or its state changes), each seen red under a break of its code.

## Acceptance

- *Needs you* holds only issues a run of this planner raised, with its reason; putting one away keeps it away in that window until its
  state or reason changes; an issue with no run here shows on the board and is not raised, and a Manual issue is
  neither.
