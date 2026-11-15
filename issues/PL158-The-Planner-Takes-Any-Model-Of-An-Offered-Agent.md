# PL158 — The Planner Takes Any Model Of An Offered Agent

```json
{
  "$schema": "issue-1.json",
  "id": "PL158",
  "title": "The Planner Takes Any Model Of An Offered Agent",
  "state": "Review",
  "size": "XS",
  "priority": "High",
  "rank": 78,
  "blockedBy": [],
  "covers": "A story's work order takes, for its main task and each changed sub-agent, any model a person chose for an agent the rule offers, not only the rule's model of that agent; a model the agent or its provider refuses fails at the task's start with its reason"
}
```

**Why this size.** XS - one check of the core's work order and its tests; the contract's members stay.

**What must be true.** A person who picks or types a model for an agent the rule offers can start the work with it.

## Plan

- Today `WorkOrder` (`planner/core`) refuses any agent and model pair the rule does not offer, for the main task and a
  changed sub-agent alike ("Choose an agent and model offered by the rule."), through `Choice.offers`, which compares
  the model with the one model the rule names for that agent.
- The check takes the agent as offered when the rule names it for the task's risk, and takes any model that is a plain
  name within the order's bounds; the rule's model stays the proposal. An agent the rule does not name is still refused.
- Red first, in the core's work order tests: a main task and a changed sub-agent with an offered agent and another model
  are taken and carry that model into the order; an agent the rule does not name is refused; an empty or unsound model
  is refused. Harm: a person who cannot start with the model they need, and an agent started that the rule never
  allowed.

## Acceptance

- The tests above, seen red against today's check; the core's suite passes.
