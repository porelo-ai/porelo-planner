# PL157 — The Start Work Wizard In The Screen

```json
{
  "$schema": "issue-1.json",
  "id": "PL157",
  "title": "The Start Work Wizard In The Screen",
  "state": "Analyzed",
  "size": "L",
  "priority": "High",
  "rank": 77,
  "blockedBy": ["PL146", "PL156"],
  "covers": "The screen package gives Start work's steps and review as the window's wizard shows them, with single choices as text and agent, model and effort as lists, as the design decides; built once the design is settled"
}
```

**What must be true.** A person starts a story's work through the wizard the design describes, with no field that is
not a choice and no value that has to be typed from memory.

**Why this size.** L - the screen package's story work steps, a hook the window answers, the plain frame, the opening
paths and their tests; several days, and a contract with the window.

## Plan

- Today `StoryWorkDialog` (`screen/lib/src/ui/story_work_dialog.dart`) draws every part of Start work in one dense
  column over `StoryWork` (`screen/lib/src/story_work.dart`), opened by `showDialog` from the card's menu
  (`screen/lib/src/ui/planning_view.dart`) and from a Needs you row (`screen/lib/src/ui/story_work_attention.dart`).
  `StoryWork` keeps every value and check (`changeMachine`, `chooseMode`, `chooseMain`, `choose`, `setComment`,
  `setRounds`, `canStart`, `start`), so the steps reuse it unchanged in their state and replace only the drawing.
- `screen/lib/src/hooks.dart` gains the wizard types the design names: `PlanningWizardStep` (title, words, panel,
  check, beforeNext, summary), `PlanningWizardActionStep` (title, run with its state, goesOnWhenLeft), `PlanningWizards`
  with `show(context, title:, steps:, action:)` answering a handle with `goBackTo(step)`, handed in beside `startTask`
  and `agents` in `PlanningStartHooks` and the boards; without it a plain frame of the screen's own shows the same steps
  with Back, Next and the review.
- A new `story_work_wizard.dart` builds the five steps from `StoryWork`: Where (machine with more than one, the proposal
  or its refusal with Retry), How (modes; one is text), Who (per task agent, model list filling a text field, effort
  list, sub-agents folded; work order view), Reading (rounds stepper, per issue folded, comment required only when a
  sub-agent changed), and the review with each step's summary and the coordinator line; Start is the action step, which
  closes once the run took the start (the board's run starts the tasks).
- Agent readiness, models and efforts come from the window through `PlanningAgents`: each agent's readiness on the
  machine and the window's words why not, and per agent the models (id, label) and effort levels, as the design says;
  the preselection takes the first ready agent in the rule's order and says who was passed over; a proposed effort is
  marked only where the agent lists it; absent effort passes none.
- A stale revision or an agent no longer ready while the wizard shows sends it back to its step (`goBackTo`).
- Both opening paths open the wizard; `StoryWorkDialog` goes.
- Red first, in the screen's story work tests over the fake hooks: one machine and one working state show no choice for
  them; the preselection skips an agent not ready and says so; a typed model is kept and started; an effort the agent
  does not list is not proposed; the review lists every choice and Change returns to its step; a stale revision goes
  back to the first step; Start closes once the start is taken. Harm: a task started with what the person did not
  choose, or with an agent that cannot run there.

## Acceptance

- The tests above, seen red against today's dialog; the screen's suite passes; the window's tests of Start work pass
  against the window's hook.
