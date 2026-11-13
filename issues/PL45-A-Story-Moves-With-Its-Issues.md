# PL45 — A Story Moves With Its Issues

```json
{
  "$schema": "issue-1.json",
  "id": "PL45",
  "title": "A Story Moves With Its Issues",
  "state": "Review",
  "size": "S",
  "priority": "Medium",
  "rank": 28,
  "blockedBy": [],
  "covers": "A story set Finished only once every issue of it stands in Review, Finished or Rejected, moving none along, and an issue move in a story whose run has started refused as WorkRunning; a story shows the state the stories index gives"
}
```

**Size:** S - two guards in `Moves` and `WorkRun` with their unit tests, the contract's words on a story move and the
screen's fake; no story state of the planner's own, so nothing new is written to a story's head.

**Estimate:** 33 min, the measured median of S.

**What must be true.** A story shows the state the stories index gives it from its issues - Reading or Review, say -
never a state the planner states on its own. A person sets a story Finished only when every one of its issues already
stands in Review, Finished or Rejected; otherwise the move is refused with an error that names the issues that do
not, the story stays where it is, and no issue is moved along. An issue move in a story whose run has started is
refused as `WorkRunning`.

## Plan

- **The operator's answers.** A story shows the index's state, so the planner writes no story state of its own and
  the stories rule stays as it is. A story set Finished moves no issue along and is refused unless all stand in
  Review, Finished or Rejected. An issue move in a story whose run has started is refused as `WorkRunning`.
- **What is built.**
  - `Moves.check` (planner/core): a story move to the completed state (closed, not Rejected) takes no issue along and
    is refused as `MoveNotAllowed` (item, from, to, reason), its reason naming each issue with its state that stands
    neither in the done state nor closed after the batch. The done state is the latest state in workflow order that
    a work rule's success leads to, Review. Other story moves keep `leastAdvanced`. A story moved Finished over
    issues in Review still does not fit them, so it is saved with its stated state only when confirmed, as any story
    that does not fit; the preview shows that as `STORY_MISMATCH`.
  - `Moves.stories` answers the stories whose running work a batch moves under: each story it moves and each story
    holding an issue it moves, directly or as a sub-issue. `MovePreview.of` passes it to the running-work callback,
    so the preview warns `RUNNING_WORK` and its digest covers that run, and `WorkRun.moves` reads the same set, so an
    unconfirmed issue move in a story whose run has a started task is refused as `WorkRunning` and a confirmed one
    stops that run.
  - `planner.yaml` says both in `saveMoves`, `MoveConflict`, `MoveRefused` and `MoveWarning`.
  - The screen: the fake (`screen/lib/src/fake/planning_moves.dart`) carries no issue on a move to a closed state
    other than Rejected and refuses it the same way, naming Review; `PlanStory.leastAdvancedIssues` takes the move's
    target and expects no issue along for such a move. The move tests' fixture closes with Rejected instead of a
    completed state, so the tests of least advanced issues keep testing them.
  - How the window shows the `WorkRunning` refusal of an issue move is agreed with the window's agent; the window
    already shows a preview's `RUNNING_WORK` warning and confirm.
- **Tests, unit, each seen red.**
  - `MovesTest`: a story set Finished while one issue stands earlier than Review is refused naming it and moves
    nothing; one whose issues all stand in Review or closed is moved alone. Harm: a story closed over open work.
  - `WorkRunTest`: an issue move in a story whose run has a started task is warned of and refused as `WorkRunning`,
    and one in a story without a run is saved. Harm: work moved under a running agent.
  - Dart (`planning_moves_test.dart`): the fake refuses the same move to Finished naming the issue and moves nothing,
    and one over issues in Review or closed takes no issue along while the board expects none.
- **Reading by another model.** A story's issues are checked with their sub-issues at any depth, so a sub-issue
  before Review keeps the story from Finished. Declined notes: "Rejected" is the stories index's own fixed name of the
  rejection, so the literal matches the store; a confirmed issue move stops its story's whole run, which the preview's
  RUNNING_WORK warning shows with its tasks before the person confirms; an issue a person works moved under a started
  run also asks that confirm, as any issue move does there; the screen's fake does not model sub-issues under a story's
  Finished, a fake for the window's tests that loses nothing.

## Acceptance

- A story set Finished over an issue still before Review is refused with the issues named, and nothing moves; one
  whose issues all stand in Review or closed moves alone. An issue move under a started run is refused. Each named
  test was seen red.
