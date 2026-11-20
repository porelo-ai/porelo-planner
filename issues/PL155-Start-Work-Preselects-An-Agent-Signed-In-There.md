# PL155 — Start Work Preselects An Agent Signed In There

```json
{
  "$schema": "issue-1.json",
  "id": "PL155",
  "title": "Start Work Preselects An Agent Signed In There",
  "state": "Implementing",
  "size": "S",
  "priority": "High",
  "rank": 75,
  "blockedBy": ["PL156"],
  "covers": "The Start work dialog preselects, for each task, the first agent of the rule's offered list that the window says is signed in on the machine the task goes to, says why it passed over the ones before it, and offers an agent not signed in there only as the person's own choice"
}
```

**Why this size.** S - the screen package's story work and its agent hook, with their tests; the hook's new member is
read by the window.

**What must be true.** A person who opens Start work finds an agent preselected that can work on the chosen machine,
and is told why an agent the rule names first was passed over.

## Plan

- Seen in a live run: the dialog preselected "Codex CLI · 0.163.0 · not signed in here" with its model, because the
  rule's models name codex first. The planner's proposal takes the first agent of the rule's offered models (`Choice.of`,
  `planner/core`) and keeps the others as offered; `StoryWork.load` (`screen/lib/src/story_work.dart`) takes the
  proposal's agent as the preselection, and the window's `PlanningAgent` (`screen/lib/src/hooks.dart`) carries only a
  name and a widget, so the screen cannot tell which agent is signed in where.
- `PlanningAgent` gets whether the agent is ready on the machine the agents were listed for, and the window's words why
  not. `StoryWork.load` keeps the proposal's agent when ready, else preselects the first offered agent, in the rule's
  order, that is ready, with the offered model, and says above the choice which were passed over and why; with none
  ready it preselects none and says so. An agent not ready stays in the list, chosen only by the person.
- The window's part: answer the new member for each agent it lists, from what it knows of the machine the task goes to.
- Red first, in the screen's story work tests over a fake agent list: codex listed first but not ready and claude ready
  preselect claude with codex named as passed over; none ready preselects none; a person's own choice of codex stays.
  Harm: a task started with an agent that cannot work there.

## Acceptance

- The tests above, seen red against today's preselection; the screen's suite passes.
