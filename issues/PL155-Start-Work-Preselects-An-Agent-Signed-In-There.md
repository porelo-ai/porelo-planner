# PL155 — Start Work Preselects An Agent Signed In There

```json
{
  "$schema": "issue-1.json",
  "id": "PL155",
  "title": "Start Work Preselects An Agent Signed In There",
  "state": "Review",
  "size": "M",
  "priority": "High",
  "rank": 75,
  "blockedBy": ["PL156"],
  "covers": "The Start work dialog preselects, for each task, the first agent of the rule's offered list that the window says is signed in on the machine the task goes to, says why it passed over the ones before it, and offers an agent not signed in there only as the person's own choice"
}
```

**Why this size.** M - one contract change, additive: the core's proposal and the planner's answer carry the rule's
offered agents, and the screen package's story work preselects from them, each with its tests.

**What must be true.** A person who opens Start work finds an agent preselected that can work on the chosen machine,
and is told why an agent the rule names first was passed over.

## Plan

- Seen in a live run: the dialog preselected "Codex CLI · 0.163.0 · not signed in here" with its model, because the
  rule's models name codex first. The proposal handed on only the first agent of the rule's offered models, so the
  screen could not choose another; the window's `PlanningAgent.unready` (`screen/lib/src/hooks.dart`) already says why
  an agent cannot work on the machine the agents were listed for, and a start with such an agent is already refused.
- Core: `WorkProposal.Main` carries `offered`, every agent the rule offers with its model, in the rule's order, the
  proposed one first, taken from `Choice` (`planner/core`).
- Contract: `PlanWorker` in `planner.yaml` gets the optional member `offered: [{agent, model}]` (`PlanOffer`), mapped
  in `WorkAnswers` and left out where the main is no proposal's, as in a run's record. It is additive: an older window
  ignores it, and a main sent back with it is accepted and never taken, as `chosenBy` is. A work record written before the
  member opens with none.
- Screen: `PlanWorker` reads `offered`, empty from an older planner. `StoryWork.load`
  (`screen/lib/src/story_work.dart`) keeps the proposal's agent when the window says it is ready there, else preselects
  the first offered agent, in the rule's order, that is ready, with its offered model, and says above the choice which
  were passed over and why, in the window's `unready` words; with none ready it preselects none and says so. Where the
  planner offered agents, one the window does not list counts as not ready, for a helper too. The proposal shows only
  once the agents are preselected. An agent not ready stays in the list, chosen only by the person, and
  a person's own choice stays; an agent chosen from the offered list gets its offered model. Helpers keep their
  proposed agent.
- Tests: core keeps the rule's order; the planner's answer carries the member and does not take one sent back; the
  screen's story work tests over a fake agent list preselect claude with codex named as passed over, preselect none
  when none is ready, keep a person's choice of codex, and keep an older planner's proposal as it was. Harm: a task
  started with an agent that cannot work there.

## Acceptance

- The tests above, seen red against today's preselection; the screen's suite passes.
