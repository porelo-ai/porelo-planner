# PL65 — Work Obeys An Issue's Worker

```json
{
  "$schema": "issue-1.json",
  "id": "PL65",
  "title": "Work Obeys An Issue's Worker",
  "state": "Estimated",
  "size": "L",
  "priority": "Medium",
  "rank": 1,
  "blockedBy": ["PL46"],
  "covers": "Only an Agent issue gets a task: the proposal and every step the planner starts by itself leave a Manual issue out, a person moves a Manual issue through the work, and a story of both kinds follows its least advanced issue"
}
```

**Size:** L - four places in the core's flow, one additive contract change (the proposal's left-out issues, a progress
reason), the app's answer and the mock, and the screen's moves, worker switch, New issue dialog and start dialog.

**Estimate:** 113 min, the measured median of L. Uncertain: the rule's `from` check against a story's state that a
Manual issue holds in a working state (see *To be checked*); if it must read the Agent issues' states instead, the
proposal step grows.

**What must be true.** An issue a person works gets no agent's task, in any start or any step the planner takes by
itself, and the person can still move it through the work; an issue an agent works goes on as today.

- The work proposal and every step the planner starts by itself leave a Manual issue out and name it, so the window
  can say so; a story whose open issues are all Manual proposes no task.
- A story with Agent and Manual issues: its Agent issues get their tasks, its Manual issues are moved by the person,
  and the story's state follows its least advanced issue. A sub-issue takes its parent's worker.
- A worker change while a task owns the issue is refused.
- A person may move a Manual issue, and only a Manual one, by hand into a working state and on from there
  (Implementing, Review and further), with no task and no dialog; an Agent issue still enters a working state only by
  its work. A story move into a working state still opens the start dialog and is refused as a plain move.
- The screen offers the switch to Manual and the choice of Manual for a new issue, the New issue dialog preselecting
  Agent, and counts no Manual issue as work without a run.

## Plan

Builds on the worker field: `Issue.worker` (`AGENT` or `MANUAL`, a head without it reads Agent, a sub-issue reads its
parent's), `PUT /projects/{project}/issues/{issue}/worker`, the refusal of a worker change while a task owns the
issue, and `NewIssueRequest.worker`. That field ships in the same release; the screen offers Manual only with this
issue.

Read in today's code:

- The story's state already follows its least advanced open issue: `GitStore.storyState` calls
  `stories.Workflow.storyState`, the minimum by workflow order over the open states. Nothing changes there.
- `WorkProposal.issues` takes every open issue of the story and its open sub-issues, of any kind; `WorkOrder.accept`
  checks a decision against that proposal, so leaving Manual out there keeps them out of every order.
- `NextStep.after` plans the next task for each issue of the ended task's binding (`NextStep.task`); the binding
  holds only proposed issues, but a worker may change between two tasks of one run, when no task owns the issue.
- `WorkRun.choose` starts the rework or the next reading of a stopped reading for an issue of the run.
- `Moves.checkOne` refuses every move into a state that has a work rule (`EnteredOnlyByWork`); moves out of one are
  already allowed. The mock's fake (`screen/lib/src/fake/planning_moves.dart`) refuses the same.
- `WorkRun.allowed` refuses a proposal while the story or one of its issues stands in a working state with no run
  ("Working states without a run need attention"), and `WorkAnswers.lost` marks such an issue `needsYou`; both would
  treat a Manual issue moved by hand as lost work.
- The screen: `PlanningDetail.moveStates` and `PlanningBoard.moveIssue` offer and stage only waiting and closed
  states; `NewIssueDialog` has no worker; the start dialog (`StoryWorkDialog`) shows the proposal's repositories only.

Steps:

1. **Core** (`core`). `WorkProposal.propose` leaves `MANUAL` issues out and lists them in a new `manual` list; a story
   whose open issues are all Manual is refused `WorkNotAllowed`, its words naming them. `NextStep.after` offers no
   task for an issue now Manual, still writes the state the ended task earned, and raises it with reason `MANUAL`.
   `WorkRun.choose` refuses a Manual issue. `Moves.checkOne` lets an issue move into a working state when it is
   Manual; a story move or an Agent issue is refused as today. `WorkRun.allowed` ignores Manual issues, and a story
   state held by Manual issues alone. Red first, unit, over the fakes in `WorkProposalTest`, `NextStepTest`,
   `MovesTest` and `WorkRunTest`: a mixed story's proposal names only its Agent issue (harm: an agent works what a
   person works); an all-Manual story is refused; a build ending done for an issue switched to Manual offers no
   reader; a Manual issue moves into Implementing and on to Review, an Agent issue's same move is refused (harm: work
   entered without its task); a story with a Manual issue in Implementing and no run still gets a proposal.
2. **Contract and app** (`planner.yaml` first, `app`, mock). `StoryWorkProposal.manual`, the issue ids left out,
   default empty; `MANUAL` in `PlanWorkIssue.reason`; the move refusal's words say a working state takes only a
   Manual issue by hand. `WorkAnswers` maps the list; `WorkAnswers.lost` skips a Manual issue. Red first, integration
   in `app` (`StoryWorkHttpTest`, `WorkRunHttpTest`): the served proposal carries `manual`, and a Manual issue in a
   working state with no run is not `needsYou`.
3. **Screen** (`screen`). A Manual issue's moves offer and stage working states too; the issue row offers the switch
   between Agent and Manual through the worker call; `NewIssueDialog` offers Agent and Manual, Agent preselected; the
   start dialog names the issues a person works; `workingCount` and `startUnavailable` count no Manual issue as work
   without a run. The fakes follow: `planning_moves.dart` lets a Manual issue into a working state, the fake proposal
   leaves it out. Red first, widget tests: a Manual issue's move to Implementing is staged and saved, an Agent
   issue's is not offered; a new issue sent without a change says `AGENT`.

Order: 1, 2, 3. Every test runs over fakes in seconds; no VM.

## Acceptance

- On a story with one Agent and one Manual issue: the start dialog proposes a task for the Agent issue only and names
  the Manual one; the Manual issue is moved by hand into Implementing and on to Review with no task and no dialog,
  and the story stands where its least advanced issue does; an Agent issue cannot be moved into a working state by
  hand; a story with only Manual issues proposes no task; an issue switched to Manual between two tasks gets no next
  task. Seen failing first: today's proposal naming the Manual issue, and today's refusal of its move.
- No Manual issue in a working state is shown as needing attention or counted as work without a run.

## To be checked

- Whether `WorkProposal.rule`'s check of the story's state against the rule's `from` must read the least advanced
  Agent issue's state when a Manual issue further back in a working state holds the story's state; measured with a
  mixed-story fixture in `WorkProposalTest`.
- Whether the screen's proposal reader (`story_work_order.dart`) refuses an unknown field, so the contract and the
  screen must change in one commit.
