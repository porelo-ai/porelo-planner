# PL156 — Start Work As A Wizard: Design

```json
{
  "$schema": "issue-1.json",
  "id": "PL156",
  "title": "Start Work As A Wizard: Design",
  "state": "Backlog",
  "size": "L",
  "priority": "High",
  "rank": 76,
  "blockedBy": [],
  "covers": "The design of Start work as a wizard of the window's kind: every field the dialog has today with whether a person needs it, where it comes from, its default and whether it is fixed or a choice; the wizard's steps and its review step; agent, model and effort as lists of what the chosen agent offers on the machine the task goes to; and how the build splits between the screen package, the planner and the window"
}
```

**Why this size.** L - a design to decide first, across the screen package, the planner's rule check and the window's
wizard and agent lists; its build is filed apart.

**What must be true.** A person who starts a story's work is led through few clear steps, chooses only what is a
choice, picks agent, model and effort from lists of what can run on that machine, and sees and can change everything on
a last step before anything starts.

## Plan

- Today's dialog (`screen/lib/src/ui/story_work_dialog.dart`, state in `screen/lib/src/story_work.dart`), read field by
  field, with what the wizard does with each:

  | Today | Comes from | Default | Needed by a person | In the wizard |
  |---|---|---|---|---|
  | Story id and title, `from → to` | the board and the move | - | yes, as context | fixed text heading every step |
  | Machine (`story-work-machine`), drop-down | each machine whose planner reports the project (window) | the board opened | only with more than one | a choice on step 1 with more than one, else not shown |
  | Working state (`story-work-target`), drop-down | the workflow's WORKING states | the move's target | no: the move or the card's menu chose it | fixed text; a menu start with several targets chooses before the wizard |
  | Problem, Retry proposal | the planner's refusal | - | yes | step 1 says it with its reason ([PL154](README.md#pl154)) and offers Retry |
  | How to work: Unattended, With you | the rule's `modes` | the first offered | yes, when the rule offers both | step 2, two cards; one mode offered is fixed text |
  | Tasks to start: repository, issues | the proposal | - | yes, as context | step 3, fixed text per task |
  | Agent of a task (`story-work-main-agent`) | the rule's offered models; the window's agent list | the rule's first | yes | step 3, a list; preselects the first agent ready on that machine, says why others were passed over ([PL155](README.md#pl155)) |
  | Model of a task (`story-work-main-model`), free text | the rule's model for that agent | the rule's | yes, but cannot be typed | step 3, a list of the chosen agent's models on that machine, the rule's marked "proposed" |
  | Effort (`story-work-main-effort`), free text, "none" | the rule's risk entry | the rule's or none | rarely | step 3, a list of the chosen agent's effort levels plus "the agent's default", the rule's marked "proposed" |
  | Sub-agents: name, role, agent, model, Keep/Change/Drop, and on Change agent, model, effort | the proposal | Keep (Drop for a non-builder) | sometimes | step 3, folded per task under "Sub-agents"; the same three lists on Change |
  | The work order per task, and all of them | the planner's order text | - | to read, not to edit | a "Show the work order" view from step 3 and the review |
  | Worked by a person, with no task | the proposal's manual issues | - | yes, as context | step 3, fixed text |
  | Coordinator: start or tell the running one | the proposal | - | to know, not to choose | the review, one line |
  | Comment | the person | empty | required when a sub-agent is changed or dropped | step 4, a full-width text area, said required only then |
  | Most reading rounds, a number field | the proposal | the rule's | rarely | step 4, a number stepper 1 to 10 with the proposal's value |
  | Per issue rounds (expander) | the person | the story's | rarely | step 4, folded "Per issue" with a stepper each |
  | Cancel, Start | - | - | yes | the wizard's Cancel and Back on every step; Start only on the review |

- The steps: 1 Where (machine, only with more than one; the planner's proposal or its refusal); 2 How (mode); 3 Who
  (per task: agent, model, effort; sub-agents folded); 4 Reading (rounds, comment); 5 Review: every choice of 1 to 4 in
  one list, each with Change going to its step, the coordinator line, the work order view, and Start. Steps with
  nothing to choose are passed over and shown only on the review.
- The wizard is the window's own wizard frame (as for adding a machine or a project), which the screen package cannot
  import, since the window depends on it. The screen describes its steps in small types of its own with the frame's
  members and asks the window to show them through a hook beside `startTask` and `agents`:
  `PlanningWizards.show(context, title:, steps:, action:)`, where a `PlanningWizardStep` has `title`, `words`, `panel`
  (a builder of its own fields only), `check` (null or why it cannot go on), `beforeNext` (an async check while the step
  shows) and `summary` (its lines on the review, in words, never a code), a `PlanningWizardActionStep` runs the start
  after the review with its running, waiting, done or refused state and `goesOnWhenLeft` (the wizard may close while the
  start goes on, as [PL146](README.md#pl146) needs), and the hook answers a handle whose `goBackTo(step)` sends the wizard back to a step
  whose answer no longer holds (a changed revision, an agent signed out meanwhile). A screen used without the window
  shows the same steps in a plain frame of its own. The review gains a Change beside each step, which the window's
  frame adds for all its wizards.
- Machine: the step names the machine the task goes to as the window decides it, and the window says why a machine
  cannot be used.
- Model and effort lists come from Porelo, which knows each agent: the window lists, for the agent and the task's
  provider on that machine, the models (id and readable name) and the effort levels each model takes, and the screen
  shows them, the rule's pair marked "proposed". What is known of the agents: codex reads its catalog with each model's
  levels and default from the CLI, omp lists its models per provider with their levels, pi and agy have list commands
  still to measure, and claude has none, so its list is declared or read from its provider. The model list depends on
  the task's provider, not only the agent. Effort names differ per agent (low to max, off or minimal on some), and
  "the agent's default" means passing none. Today no agent's start passes an effort at all, so the dialog's Effort field
  has no effect; Porelo's agent definitions gain an effort flag and a list of models, which is Porelo's contract and
  each agent's adapter, filed there.
- The build splits into: the screen package's wizard steps and state, with [PL153](README.md#pl153) and [PL155](README.md#pl155) folded in (one issue of this
  repository, its step types and hook among them); the planner's rule check, if the open question below is answered yes; [PL154](README.md#pl154) stays the core's own and
  is shown on step 1; the window's wizard frame and its agent, model and effort lists (the window's issues).

## Acceptance

- The operator has read the design, settled its open question, and the build issues are filed with their sizes in the
  screen package, the planner and the window.

## Open questions

- May a person start an agent with a model of that agent the rule does not name? Today the planner refuses any pair
  the rule does not offer ("Choose an agent and model offered by the rule."), and a rule names one model per agent; a
  yes lets the list offer every model the agent names there, and the planner checks only that the agent is offered.

## To be checked

- From Porelo's and the window's agents: how the window asks Porelo for an agent's models and levels for a machine and
  a provider, once Porelo's agent definitions carry them.
- From the window's agent: its issue for the hook, the review's Change per step, the machine step's answers and the
  agent list's readiness ([PL155](README.md#pl155)'s member).
