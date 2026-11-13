# PL45 — A Story Moves With Its Issues

```json
{
  "$schema": "issue-1.json",
  "id": "PL45",
  "title": "A Story Moves With Its Issues",
  "state": "Implementing",
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

- **Today, read in the code.**
  - A story's state is its head's stated state, or else the stories index's rule (`Workflow.storyState` in
    `store/git/stories`): the earliest open issue state in workflow order.
  - `Moves.check` moves a story's least advanced open issues along with every story move (`leastAdvanced`), a move
    to Finished included.
  - `WorkRun` refuses an issue move as `WorkRunning` only while running work owns that issue or one of its
    sub-issues (`refuseOwned`).
- **The operator's answers.** A story shows the index's state, so the planner writes no story state of its own and
  the stories rule stays as it is. A story set Finished moves no issue along and is refused unless all stand in
  Review, Finished or Rejected. An issue move in a story whose run has started is refused as `WorkRunning`.
- **The change.**
  - `Moves.check`: a story move to the completed state (closed, not Rejected) takes no issue along and is refused,
    naming the issues that stand elsewhere, unless every issue stands in the done state or closed. Other story
    moves keep `leastAdvanced` as today.
  - `WorkRun.moves`: an issue move is refused as `WorkRunning`, naming the story and its run, while the issue's story
    has a run with a started task.
  - `planner.yaml` (`saveMoves`) says both; the screen's fake (`screen/lib/src/fake/planning_moves.dart`) and
    `PlanStory.leastAdvancedIssues` take no issue along on a move to Finished.
  - How the window shows the `WorkRunning` refusal of an issue move is agreed with the window's agent before it is
    built.
- **Tests, unit, red first.**
  - `MovesTest`: a story set Finished while one issue stands earlier than Review is refused and moves nothing; one
    whose issues all stand in Review or closed is moved alone. Harm: a story closed over open work.
  - `WorkRunTest`: an issue move in a story whose run has a started task is refused as `WorkRunning`, and one in a
    story without a run is saved. Harm: work moved under a running agent.
  - Dart: the fake refuses the same move to Finished and takes no issue along.

## Acceptance

- A story set Finished over an issue still before Review is refused with the issues named, and nothing moves; one
  whose issues all stand in Review or closed moves alone. An issue move under a started run is refused. Each named
  test was seen red.
