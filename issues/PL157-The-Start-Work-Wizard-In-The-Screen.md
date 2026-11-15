# PL157 — The Start Work Wizard In The Screen

```json
{
  "$schema": "issue-1.json",
  "id": "PL157",
  "title": "The Start Work Wizard In The Screen",
  "state": "Implementing",
  "size": "S",
  "priority": "High",
  "rank": 77,
  "blockedBy": ["PL146", "PL156"],
  "covers": "The screen package gives Start work's steps and review as the window's wizard shows them, with single choices as text and agent, model and effort as lists, as the design decides; built once the design is settled"
}
```

**What must be true.** A person starts a story's work through the wizard the design describes, with no field that is
not a choice and no value that has to be typed from memory.

**Why this size.** S - today's dialog split into step panels over the unchanged `StoryWork`, a small hook the window
answers, and the tests of the steps' checks.

## Plan

- Today `StoryWorkDialog` (`screen/lib/src/ui/story_work_dialog.dart`) draws every part of Start work in one dense
  column over `StoryWork` (`screen/lib/src/story_work.dart`), opened by `showDialog` from the card's menu
  (`screen/lib/src/ui/planning_view.dart`) and from a Needs you row (`screen/lib/src/ui/story_work_attention.dart`).
  `StoryWork` keeps every value and check (`changeMachine`, `chooseMode`, `chooseMain`, `choose`, `setComment`,
  `setRounds`, `canStart`, `start`), so the steps reuse it unchanged in their state and replace only the drawing.
- `screen/lib/src/hooks.dart` gains a small hook beside `startTask` and `agents`: `PlanningWizards.show(context,
  title:, steps:, action:)`, each step with title, words, its panel (today's field widgets, moved out of the dialog),
  `check` (null or why it cannot go on) and its review lines; the window maps it onto its Wizard; without it a plain
  frame of the screen's own shows the same steps.
- The steps as the design note says. The checks the plan reading asked for: choosing another agent resets its model
  and effort to the proposed ones (an effort the agent does not list is dropped); a model is one trimmed line within
  the planner's bounds; step 1 is never passed over while the proposal loads or was refused, and Cancel stays enabled
  then; a Change from the review checks the later steps again before the review shows (a comment newly required);
  Start is the action step and closes only once the planner took the order, the remaining starts going on in the
  board's run ([PL146](README.md#pl146)).
- Both opening paths open the wizard; `StoryWorkDialog` goes.
- Red first, in the screen's story work tests over the fake hooks: a single machine and mode show as text; another
  agent resets model and effort; a typed model reaches the start; step 3 refuses an empty model; the review lists every
  choice and a Change that makes the comment required stops at step 4. Harm: a task started with what the person did not
  choose or see.

## Acceptance

- The tests above, seen red against today's dialog; the screen's suite passes.
