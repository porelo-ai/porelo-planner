# PL156 — Start Work As A Wizard: Design

```json
{
  "$schema": "issue-1.json",
  "id": "PL156",
  "title": "Start Work As A Wizard: Design",
  "state": "Review",
  "size": "XS",
  "priority": "High",
  "rank": 76,
  "blockedBy": [],
  "covers": "A short design note: the wizard's steps, which of today's dialog fields go to which step, the review, model as a list that fills a text field, effort from the agent's listed efforts"
}
```

**Why this size.** XS - a short note; the window's wizard exists and the dialog's field code is reused.

**What must be true.** A person starts a story's work through the window's wizard, with today's dialog split into a few
steps and everything shown once more before Start.

## Plan

- The steps, each taking today's fields from `screen/lib/src/ui/story_work_dialog.dart`:
  1. Where: the machine (a choice only with more than one) and the planner's proposal, or its refusal with its reason
     ([PL154](README.md#pl154)) and Retry. Never passed over while the proposal loads or was refused.
  2. How: Unattended or With you; one offered mode is shown as text.
  3. Who: per task its repository and issues, the agent (the first one ready there preselected,
     [PL155](README.md#pl155)), the model as a list of the agent's models with the rule's marked proposed that fills a
     text field which can also be typed ([PL158](README.md#pl158)), the effort as a list of the agent's listed
     efforts (`Agents().efforts`) plus "the agent's default" (passes none), the rule's effort proposed only where the
     agent lists it; sub-agents folded with Keep, Change, Drop; the issues worked by a person, as text.
  4. Reading: most reading rounds, per issue folded, and the comment, required only when a sub-agent changed or dropped.
  5. Review: every choice in one list with Change to its step, the coordinator line, the work order view, and Start.
- Fixed values are text, never a one-entry list ([PL153](README.md#pl153)): the story and its move head every step,
  the working state is the move's.
- The window's wizard shows the steps through a screen hook; the window adds Change per step on its review.

## Acceptance

- The note stands and the screen's build ([PL157](README.md#pl157)) follows it.
