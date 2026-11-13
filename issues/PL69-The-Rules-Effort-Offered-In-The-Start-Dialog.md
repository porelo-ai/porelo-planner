# PL69 — The Rule's Effort Offered In The Start Dialog

```json
{
  "$schema": "issue-1.json",
  "id": "PL69",
  "title": "The Rule's Effort Offered In The Start Dialog",
  "state": "Implementing",
  "size": "L",
  "priority": "Medium",
  "rank": 2,
  "blockedBy": ["PL66"],
  "covers": "The model and effort the work rules choose for an issue's risk reach the window with where the choice came from; the start dialog proposes the rule's effort, the person may change it, and the effort chosen is handed to the window's Start"
}
```

**Size:** L - the window's contract, the app's answers and the mock board, and the screen's start dialog, with their
tests. It carries the effort through the contract and the screen; the effort takes hold once Porelo's Start takes
one, which this issue does not wait for.

**Estimate:** 113 min, the measured median of L; its steps sum to 100 (M, S).

**What must be true.** A person starting a task sees the model and effort the work rules chose for its issues' risk,
and where that choice came from, beside the agent; the rule's effort is proposed and the person may change it, as they
may choose among the rule's models today, and the effort chosen is the one the window starts. A task that starts by
itself starts with the rule's effort. The choice itself is made in the core by [PL66](README.md#pl66).

## Plan

What stands today, read in the code:

- Nothing in this repository carries an effort to the window: the contract's `PlanningStartTask`, `PlanWorker` and
  `PlanWorkTask` name `agent`, `model` and `mode` only, and the screen's `PlanningTaskOrder` reads exactly those and
  hands them to the window's `PlanningStartTask` hook. What the window's Start takes for an effort is not visible
  here. Only the stage records (`StageRecords`) keep a task's `effort`, as reported after the task.
- `WorkOrder.offered` (core) refuses a chosen agent or model the rule does not name; the start dialog offers the
  rule's models with the first selected.

The shape:

- The served start and work answers carry `effort` (absent when no rule names one) and `chosenBy`, the words of the
  core's choice, for example "Reading, risk harm".
- The start dialog shows the effort beside the model, the rule's effort selected; the person may change it. A changed
  effort is a name checked only for length and control characters, as the planner checks a model's name; whether an
  agent takes it is the window's Start's to say. The rule's agents and models stay the only ones offered.
- A task that starts by itself takes the rule's effort, with no dialog.

Steps:

1. **The contract and the mock board** (M). Written first: `PlanningStartTask` gets `effort` and `chosenBy`;
   `PlanWorker` and `PlanAssignment` get `effort` and `chosenBy`; `PlanWorkTask` gets `effort`. `WorkOrder.offered`
   takes a chosen effort, checked as a name only. `BoardController` and `WorkAnswers` serve them; the mock board's
   rules (`MockStore`) name an effort for `harm`. Red first, in `app`: a served start for a `harm` issue carries the
   rule's effort; a start with an effort the person changed carries the changed one; an effort with a control
   character or over the length bound is refused. Harm prevented: the window starting another effort than the rule
   set or the person chose, or a name that reaches the window's Start unchecked.
2. **The screen** (S). `PlanningTaskOrder` reads `effort` and `chosenBy` and hands them to the window's hook; the start
   dialog shows both beside the model, the rule's effort selected and changeable; the screen sends `PlanWorker` back
   with the effort chosen, since it allows no unknown key. Red first, in `screen`: an order without `effort` reaches
   the hook without one, one with it keeps it, and one whose effort the person changed reaches the hook with the
   changed one. Harm prevented: an effort dropped or replaced between the planner, the person and the window.

Order: 1, 2. Built after [PL66](README.md#pl66), whose choice it serves.

Needs from other repositories, in words:

- The window agreed to the members: `PlanningStartTask`, `PlanWorker` and `PlanAssignment` gain `effort` and
  `chosenBy`, `PlanWorkTask` gains `effort`; `effort` is an open string, not an enum, since its values are Porelo's
  per agent, and it is optional (null) in `PlanWorker`, which carries back the effort actually used.
- Neither the window's Start nor Porelo's takes an effort yet. Until Porelo's does, the window starts without one,
  says so on its form, and sends back no effort; the screen shows the offered effort as not used.

## Acceptance

- With a work rule that names an effort for `harm`: a Reading started by itself for a `harm` issue reaches the window's
  hook with that rule's model and effort and the words "Reading, risk harm"; a task started from the dialog with the
  effort changed reaches the hook with the changed effort; a project whose rules name no `risks` starts as today. Each
  test named above is seen red first.
- The screen module's Maven build passes locally: `dart format`, `dart analyze --fatal-infos` and `flutter test`.
