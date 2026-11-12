# PL66 — An Issue's Risk Chooses Its Model And Effort

```json
{
  "$schema": "issue-1.json",
  "id": "PL66",
  "title": "An Issue's Risk Chooses Its Model And Effort",
  "state": "Review",
  "size": "L",
  "priority": "Medium",
  "rank": 1,
  "blockedBy": [],
  "covers": "An issue's head may say how much harm its change can do, the work rules give per working state the model and effort for each risk, and the planner's core proposes that choice for every task it offers or starts by itself"
}
```

**Size:** L - the issue's head and the work rules in the store, the choice in the core and the work record that
saves it, with their tests; it needs the build tools' issue schema to take the new head field.

**Estimate:** 113 min, the measured median of L; its steps sum to 100 (M, S), before the plan's reading added a
third (S).

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
  chosen agent or model the rule does not name; `NextStep.Settings.of`, which `WorkRun.reworkSettings` calls, checks
  a rework's chosen agent and model against the rule's `models` and, given none, fills in the rule's first.
- A work rule and a task's arguments are saved in the work record (`WorkRecord`, an offer's `binding.rule` and
  `arguments`), read back with every field required; only the offer's `machine` and `rework` may be absent.
- A work rule checks its agents and models only for being blank; `NextStep.task` refuses one over 128 characters.

The shape:

- An issue's head may carry `risk`: `harm`, `ordinary` or `small`; a head without it reads `ordinary`, an unknown
  value is a fault that leaves the issue out as any broken head does. A sub-issue is worked with the higher of its own
  risk and its parent's, so neither a head of its own nor its parent's lowers it. The head writes `risk` last, after
  `worker`, as the build tools write it, and setting a worker on a head that has none puts it before the risk; the
  store's copy of `issue-1.json` takes it too, and the work tools' fixture holds a head with a risk, so the planner's
  `issues check` and the build tools' are seen to agree on it.
- A work rule may carry `risks`, a mapping from a risk to `models` and `effort`, each optional; what a risk does not
  name comes from `ordinary`'s entry, then from the rule's own `models` and no effort. A risk's `models` replaces the
  rule's whole, never agent by agent, and is all the window may choose from for that risk; it may name an agent the
  rule's own `models` does not. A risk other than the three, an entry that is no mapping or names neither `models`
  nor `effort`, an empty `models`, or an agent, model or effort that is blank, longer than 128 characters or holds a
  control character is a fault that drops the rule with its words. An effort is a name the planner passes on
  unchecked but for these; what it means for each agent is Porelo's.
- A task over several issues takes the highest risk among them (`harm` over `ordinary` over `small`, by an order the
  risk states, never by its declaration), so a careful choice is never lost to a lighter issue beside it; a task with
  no issue, as a story's own work or a discussion, is `ordinary`.
- Wherever the core chooses or checks an agent and model it takes the task's risk: the proposal's main by its
  repository's issues and each helper by its own issue, `WorkOrder` for which a decision may choose, and a rework's
  settings in `NextStep.Settings.of`, which given none leaves the choice to the risk instead of the rule's first model.
- Every place that rebuilds an issue or a task's arguments passes the risk and the effort on: `Issue` keeps no
  constructor that names a worker but no risk, and `WorkRun` copies a task's effort where it copies its arguments. A
  decision from the window carries no effort yet; the effort comes from the proposal the decision is checked against.
- The work record reads a record written before the risks: an offer's rule without `risks` reads as naming none, its
  arguments without `effort` as having none, and the effort saved is held to the rule's bounds.
- Each choice names where it came from in plain words, for example "Reading, risk harm", beside the agent, model and
  effort.
- The chosen effort is the proposal, as the rule's first model is today; the person's change of it in the start
  dialog, and what the planner accepts for a changed effort, are [PL69](README.md#pl69).

Steps:

1. **Risk and rules in the store** (M). `Issue.risk`; `StoreReader` and the store's `IssueSchema`/`IssueHead` read
   it; `WorkRule.risks` and `WorkReader` read the new key, a fault in it drops the rule with its words. Red first, in
   `StoreReaderTest` over its fake remote: a head with `risk: harm` reads harm, one without reads ordinary, a
   sub-issue reads the higher of its own and its parent's, `risk: Harm` is a fault; in `WorkReaderTest`, a rule's
   `risks` is read and a wrong one drops only that rule; in `IssueHeadTest`, a head written keeps its risk last; in
   `HeadEditTest`, a worker set on a head with a risk goes before it. Harm
   prevented: an issue that can do harm read as ordinary, or a bad rule taking others down.
2. **The choice in the core** (S). One small `Choice` (agent, model, effort, words) made from a rule and the task's
   issues, used by `WorkProposal.propose`, `NextStep.task`, `IdeaDiscussion`, and by `WorkOrder.offered` for which
   agents and models may be chosen. Red first, unit, in `NextStepTest`: a Reading task for a `harm` issue carries the
   rule's effort for `harm`; in `WorkProposalTest`, a story of a `small` and a `harm` issue in one repository is
   proposed with `harm`'s model; in `WorkOrderTest`, a decision choosing a model `harm` does not offer for a `harm`
   task is refused; a rework chosen with no settings for a `harm` issue runs with `harm`'s model. Harm prevented: work
   that can do harm proposed with a light model or effort, or a window choosing one the rule does not offer.
3. **Older work records** (S). `WorkRecord` fills in the absent `risks` and `effort` of a record written before
   them. Red first, in `WorkRecordTest`: a record with both removed still opens and keeps its offer. Harm prevented:
   an upgrade that makes every running story's work record unreadable.

Order: 1, 2, 3. Built on the issue's worker field, which changed the same head reading, schema copy and `Issue`.

The plan's reading by another model asked ten questions, all taken in above but one part of one: a rule that names a
lighter model for `ordinary` and none for `harm` works a `harm` issue as `ordinary`. That stands as designed: the
planner cannot weigh models, and a rule that names no `harm` says that harm is worked as ordinary.

The code's reading by another model found two things:

- Fixed: a record written before the risks whose run names repositories did not open, since a repository's main and
  its sub-agents gained an effort and a basis the record requires. `WorkRecord` fills in no effort and an empty basis
  for both, and `WorkRecordTest` opens such a record with a main and one sub-agent.
- Declined: a decision that names its repositories saves them in the run without the proposal's effort and basis.
  Nothing chooses a task from the saved repositories: a task's effort comes from the proposal when it is ordered and
  is saved in its offer's arguments, a rework's from the rule and its issue's risk, and the window is answered the
  saved repositories without an effort or a basis. Handing the effort to the window is where that changes.

Needs from other repositories, in words:

- The build tools' issue schema and head take an optional `risk`, a string of the values `harm`, `ordinary` and
  `small`, written last, after `worker`, so their `issues check` accepts it and their head writer keeps it. Their
  snapshot does, and the work tools' test runs both checks on a head that carries one.
- A project's `stories/work.yml` takes `risks` only after the planner that reads it ships, since today's planner drops
  a rule with an unknown key.

## Acceptance

- With a work rule that names an effort for `harm`: a Reading started by itself for a `harm` issue is chosen with that
  rule's model and effort and the words "Reading, risk harm"; the same issue without `risk` gets the `ordinary`
  choice; a project whose rules name no `risks` is chosen for as today. Each test named above is seen red first.
