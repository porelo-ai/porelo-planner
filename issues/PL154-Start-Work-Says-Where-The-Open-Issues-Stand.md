# PL154 — Start Work Says Where The Open Issues Stand

```json
{
  "$schema": "issue-1.json",
  "id": "PL154",
  "title": "Start Work Says Where The Open Issues Stand",
  "state": "Implementing",
  "size": "XS",
  "priority": "High",
  "rank": 74,
  "blockedBy": [],
  "covers": "When no open issue of a story stands where the chosen rule takes from, the planner's refusal names each open issue with its state and the states the rule takes from, so the person sees why nothing can start"
}
```

**Why this size.** XS - one refusal of the core's work proposal and its test; the refusal's name stays.

**What must be true.** A person whose Start work finds nothing to start reads which open issues there are, where they
stand and where the rule takes from, instead of a sentence that names none.

## Plan

- Seen in a live run: a story with seven of eight issues done and its one open issue in Idea answered Start work toward
  Implementing with "No open issue of the story stands where the rule takes from." `WorkProposal`
  (`planner/core`) throws that `NotAllowed` refusal when no open issue an agent works stands in the rule's `from`.
- The reason names each open issue an agent works with its state, then the rule's working state and its `from` states,
  for example "PJ111 stands in Idea; Implementing takes from Backlog and Rework.", within the refusal's bound on its
  reason; the refusal's name and parameters stay as the window reads them.
- Red first, in the core's work proposal tests: a story whose one open issue stands in Idea, asked toward a rule taking
  from Backlog and Rework, is refused with a reason naming the issue, its state and both states. Harm: a person who
  cannot tell why the story's work does not start.

## Acceptance

- The test above, seen red against today's refusal; the core's suite passes.
