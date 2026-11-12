# PL66 — An Issue's Risk Chooses Its Model And Effort

```json
{
  "$schema": "issue-1.json",
  "id": "PL66",
  "title": "An Issue's Risk Chooses Its Model And Effort",
  "state": "Backlog",
  "size": "L",
  "priority": "Medium",
  "rank": 1,
  "blockedBy": [],
  "covers": "An issue's head may say how much harm its change can do, the work rules give per working state the model and effort for each risk, and the planner's core proposes that choice for every task it offers or starts by itself"
}
```

**Size:** L - the issue's head and the work rules in the store, and the choice in the core, with their tests; it needs
the build tools' issue schema to take the new head field.

**Estimate:** 113 min, the measured median of L; its steps sum to 100 (M, S).

**What must be true.** A task that works an issue whose change can do harm is proposed the model and effort the
project's work rules set for that risk in that working state, with the words saying where the choice came from; an
issue that names no risk is worked as an ordinary one. The rule's effort is a proposal: the person may change it in the
start dialog, as they may choose among the rule's models today. Handing the effort to the window and changing it in
the start dialog is [PL69](README.md#pl69).

## Plan

What stands today, read in the code:

- An issue's head carries no risk: `Issue` (`store/api`) has no such field, and the store's copy of the issue schema
  (`IssueSchema`, `IssueHead` in `store/git/stories`) knows no such key. The build tools' `issue-1.json` takes
  `worker` but no `risk` and allows no other key, so `issues check` refuses a head that carries one.
- A work rule (`WorkRule`, read by `WorkReader` from `stories/work.yml`) names `models`, one model per agent, the first
  the default; `WorkReader` refuses an unknown key, which drops the whole rule. No rule names an effort.
- The core takes the rule's first agent and model for every task: `WorkProposal.propose` (main and helpers),
  `NextStep.task` (a reading or rework that starts by itself) and `IdeaDiscussion`. `WorkOrder.offered` refuses a
  chosen agent or model the rule does not name.

The shape:

- An issue's head may carry `risk`: `harm`, `ordinary` or `small`; a head without it reads `ordinary`, a sub-issue
  takes its parent's, an unknown value is a fault that leaves the issue out as any broken head does.
- A work rule may carry `risks`, a mapping from a risk to `models` and `effort`, each optional; what a risk does not
  name comes from `ordinary`'s entry, then from the rule's own `models` and no effort. An effort is a name the planner
  passes on unchecked but for length and control characters, as a model is; what it means for each agent is Porelo's.
- A task over several issues takes the highest risk among them (`harm` over `ordinary` over `small`), so a careful
  choice is never lost to a lighter issue beside it; a discussion has no issue and is `ordinary`.
- Each choice names where it came from in plain words, for example "Reading, risk harm", beside the agent, model and
  effort.
- The chosen effort is the proposal, as the rule's first model is today; the person's change of it in the start
  dialog, and what the planner accepts for a changed effort, are [PL69](README.md#pl69).

Steps:

1. **Risk and rules in the store** (M). `Issue.risk`; `StoreReader` and the store's `IssueSchema`/`IssueHead` read
   it; `WorkRule.risks` and `WorkReader` read the new key, a fault in it drops the rule with its words. Red first, in
   `store/git` over local bare repositories: a head with `risk: harm` reads harm, one without reads ordinary, a
   sub-issue reads its parent's, `risk: Harm` is a fault; in `WorkReaderTest`, a rule's `risks` is read and a wrong
   one drops only that rule. Harm prevented: an issue that can do harm read as ordinary, or a bad rule taking others
   down.
2. **The choice in the core** (S). One small `Choice` (agent, model, effort, words) made from a rule and the task's
   issues, used by `WorkProposal.propose`, `NextStep.task`, `IdeaDiscussion`, and by `WorkOrder.offered` for which
   agents and models may be chosen. Red first, unit, in `NextStepTest`: a Reading task for a `harm` issue carries the
   rule's effort for `harm`; in `WorkProposalTest`, a story of a `small` and a `harm` issue in one repository is
   proposed with `harm`'s model. Harm prevented: work that can do harm proposed with a light model or effort.

Order: 1, 2. Built on the issue's worker field, which changed the same head reading, schema copy and `Issue`.

Needs from other repositories, in words:

- The build tools' issue schema and head take `risk` with the values `harm`, `ordinary` and `small`, written in a
  fixed place after the other optional fields, so `issues check` accepts it and the index writer keeps it. Until then
  the planner reads it but no head may carry it.
- A project's `stories/work.yml` takes `risks` only after the planner that reads it ships, since today's planner drops
  a rule with an unknown key.

## Acceptance

- With a work rule that names an effort for `harm`: a Reading started by itself for a `harm` issue is chosen with that
  rule's model and effort and the words "Reading, risk harm"; the same issue without `risk` gets the `ordinary`
  choice; a project whose rules name no `risks` is chosen for as today. Each test named above is seen red first.
