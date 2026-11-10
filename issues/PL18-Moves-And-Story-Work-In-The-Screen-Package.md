# PL18 — Moves And Story Work In The Screen Package

```json
{
  "$schema": "issue-1.json",
  "id": "PL18",
  "title": "Moves And Story Work In The Screen Package",
  "state": "Implementing",
  "size": "L",
  "priority": "Medium",
  "rank": 9,
  "blockedBy": ["PL05", "PL08"],
  "covers": "The screen package's HTTP calls for saving moves, proposing story work and ordering it, and the window's Start hook taking each checked task once, so the window can start a story's work"
}
```

**Size:** L - Several days for the HTTP calls, work-order and runtime models, window hooks, fake lifecycle and contract tests across the package and its window integration.

**What must be true.** A window that embeds the planning screen can save a story's moves, see the work a working
state proposes, and start its tasks with Porelo's own `Start`: the package's `HttpPlanningSource` calls the planner
for moves, the proposal and the order, with its refusals answered as `PlanningRefusal`, and hands the window each
checked task once. Following and stopping the work is [PL24](README.md#pl24).

## Plan

- **Contract and order:** use the served `planner/app/src/main/resources/openapi/planner.yaml`, `BoardController`,
  `WorkRequests` and `WorkAnswers` for moves, proposals and work orders. Change the screen's models and fake to
  match them: an order answers `{revision,tasks}`, starts nothing and changes no planning state. Following and
  stopping work, findings, task reports and reading decisions are [PL24](README.md#pl24).
- **HTTP transport and moves:** in `screen/lib/src/http/planner_source.dart`, replace `HttpPlanningSource`'s local
  unsupported answers with authenticated calls using `_told`, `_uri` and `_call`. Save one ordered batch with
  `POST /projects/{project}/moves`, `{revision,moves:[{story|issue,to}]}`, omitting `confirm` to retain false; decode
  `{revision,moved}` as `PlanningMoveResult`. Keep `PlanningBoard.save`'s pending batch and explicit retry on
  refusal; never replay a write after a timeout, lost connection, Busy or PartlySaved. Refresh actual board states
  after PartlySaved without retrying or rolling back. Retell and retry after an explicit NoSuchProject refusal
  only. Make success-envelope checks specific to each call:
  `_answer` currently requires revision even for the planned `{work}` replies. Extend `_problem` to preserve
  bounded typed parameters, including `tasks` lists and numeric rounds, as `PlanningRefusal`, with name and detail
  intact. NotOffered remains a named refusal and disables only that action.
- **Proposal and Start:** add `startStoryWork(StoryWorkRequest)` to `PlanningSource` in `screen/lib/src/client.dart`
  and its HTTP implementation. Proposal is `POST /projects/{project}/stories/{story}/proposal`, `{to}`;
  work is `POST /projects/{project}/stories/{story}/work`, `StoryWorkRequest.toMap()`'s
  `{project,story,to,mode,revision,repositories?,comment?,readingRounds?,issueReadingRounds?}`. In
  `screen/lib/src/models.dart`, give `StoryWorkStarted` typed top-level Start arguments
  `{repository,issues,role,agent,model,mode,prompt}`, adding the planned offer identity when supplied by the run.
  Validate the complete task list before any Start. Separate order acceptance from confirmed runtime activity;
  remove the requirement for `work.atWork` and derive
  terminal identities only from actual starts/reports. In `screen/lib/src/hooks.dart`, retain `PlanningStartTask`
  as the window's orchestration hook: it requests one order from the selected source and passes each returned
  task, including its exact prompt, to Porelo's Start. Neither the screen nor its HTTP source starts a process.
  Update `StoryWork` in `screen/lib/src/story_work.dart` and `StoryWorkDialog` in
  `screen/lib/src/ui/story_work_dialog.dart` for this distinction, retaining provider/project/story/revision binding,
  one pending submission and initial reading limits 1..10.
- **Fake and red first:** align `MockStoryWork` in `screen/lib/src/fake/story_work.dart`, `MockPlanningSource`,
  `MockPlanningClient` and `mockStartTask` in `screen/lib/src/fake/mock_planning.dart`, and test sources under
  `screen/test/support/`. Preparing an order must not move states or mark work active; readers follow building,
  STOPPED clears activity, and completed readings alone increment rounds. First add loopback HTTP integration
  cases in `screen/test/planner_source_test.dart` for exact requests, envelopes and refusals (harm: a move saved
  twice or a refusal shown as success), replacing the blanket unsupported expectation. Add unit cases in
  `story_work_records_test.dart` for prepared versus running work (harm: false success), and
  `story_work_sources_test.dart`/`story_work_lifecycle_test.dart` with a recording window hook for source/story
  binding, late answers and exact Start fields/prompts (harm: another story's task or the wrong prompt started).
  Observe these guards fail before fixing behavior, with served fixtures. Finish with the screen Maven module's clean
  format/analyze/Flutter test checks, document checks and an unreleased YAML changelog entry.


### Taken from the plan reading

These hold over the points above where they differ.

- **Nothing reaches Start from a late or foreign answer.** The screen asks for the order itself
  (`startStoryWork`) and hands its tasks to the window's Start only while the board, its source, its project binding
  and the story are those the order was asked for; the same holds for tasks a reading decision answers. A write, an
  order, a stop or a decision is refused when the project is no longer told as the board was read from.
- **No task twice.** A story with an accepted order or a pending start offers no second start until a report or a
  board shows the work or the start fails; an offer handed to Start once is never handed again, and offers answered
  after a stop was asked are dropped.
- **An answer is taken whole or not at all.** The three task-returning answers are refused before any Start unless
  every task carries a valid repository, issue ids, an allowed role (MAIN or BUILDER for an order, READER too after),
  non-blank agent and model, an allowed mode, a prompt of at most 64 KiB and, once served, an offer; an order's
  answer must carry the request's revision and exactly the repositories, issues, roles, agents, models and mode
  chosen. Only a BUILDER may be kept or changed at start. Lenient model helpers that drop or blank fields are not
  used for these answers.
- **A refusal is shown, never taken as success.** Any refusal of a save sets the board's move problem and leaves
  nothing shown as moved; PartlySaved reloads the board and keeps its refusal with what was pushed and what failed.
  NotOffered disables only the action refused. `{work}` must hold `work` as null or a valid run; an explicit null is
  no run. A streamed board is suppressed only when it equals the last one delivered.
- **Bounds and shown text.** A refusal's name, its task list and its numbers are bounded; control characters and
  bidirectional overrides are stripped from every refusal word, finding and raw answer shown.
- **Cut.** The terminal-name derivation goes; the window knows what it started. The run's calls are built only once
  the planner serves them, without staged fixtures, in [PL24](README.md#pl24). The README stays as it is.

### Decided with the window

- **The Start hook, as agreed with the window.** `PlanningStartTask = Future<PlanningTaskStarted> Function(PlanningSource
  source, String project, String story, PlanningTaskOrder task)`; `PlanningTaskOrder` is `{offer, repository, issues,
  role, agent, model, mode, prompt}`, `PlanningTaskStarted` is `{task, container}` as Porelo's Start answers. The screen
  asks for the order through `PlanningSource.startStoryWork`, checks it whole, and hands the window one task at a time;
  tasks a reading decision or a task's end answers take the same hook. The window picks the machine; the order names
  none. A refused Start throws `PlanningRefusal` with Porelo's words: nothing further of that order starts, and the
  screen shows the refusal on that task, not the order as failed, since earlier tasks may already run.
  `StoryWorkStarted` and its `terminalName` go.

## Acceptance

- A reviewed mixed story/issue batch reaches the selected planner in one POST with its captured revision;
  staging/undo sends none. A refused or unanswered save is never reported as saved or automatically repeated.
- Proposal and order requests match the served contract. Each returned task reaches the window's Start once
  with unchanged repository, issues, role, agent, model, mode and prompt. An order alone never marks work running;
  switching source or story cannot redirect a pending start or its terminal.
- Typed refusals retain their names, parameters and detail; no refused order starts tasks; a refused Start stops the
  rest of its order and shows on that task alone. Unit and loopback HTTP guards have been seen red then green; the full screen checks and document checks pass.
