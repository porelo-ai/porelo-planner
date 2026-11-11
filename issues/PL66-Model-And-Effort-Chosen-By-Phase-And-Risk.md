# PL66 — Model And Effort Chosen By Phase And Risk

```json
{
  "$schema": "issue-1.json",
  "id": "PL66",
  "title": "Model And Effort Chosen By Phase And Risk",
  "state": "Analyzed",
  "size": "XL",
  "priority": "Medium",
  "rank": 1,
  "blockedBy": ["PL46"],
  "covers": "An issue's head may say how much harm its change can do, and the work rules give per working state the model and effort for each risk, which a start hands to the window",
  "openQuestions": 1
}
```

**Size:** XL - the issue's head and the work rules in the store, the choice in the core, the window's contract, the
mock board and the screen, and it needs two other repositories; it splits into two issues of L.

**Estimate:** 226 min, two medians of L for its two parts; its steps sum to 200 (M, S, M, S). Uncertain: whether the
window's Start takes an effort at all is not known here; if it does not, the second part waits on the window.

**What must be true.** A task that works an issue whose change can do harm gets the model and effort the project's
work rules set for that risk in that working state, and the person sees where the choice came from; an issue that names
no risk is worked as an ordinary one.

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
- Nothing in this repository carries an effort to the window: the contract's `PlanningStartTask`, `PlanWorker` and
  `PlanWorkTask` name `agent`, `model` and `mode` only, and the screen's `PlanningTaskOrder` reads exactly those and
  hands them to the window's `PlanningStartTask` hook. What the window's Start takes for an effort is not visible
  here. Only the stage records (`StageRecords`) keep a task's `effort`, as reported after the task.

The shape:

- An issue's head may carry `risk`: `harm`, `ordinary` or `small`; a head without it reads `ordinary`, a sub-issue
  takes its parent's, an unknown value is a fault that leaves the issue out as any broken head does.
- A work rule may carry `risks`, a mapping from a risk to `models` and `effort`, each optional; what a risk does not
  name comes from `ordinary`'s entry, then from the rule's own `models` and no effort. An effort is a name the planner
  passes on unchecked but for length and control characters, as a model is; what it means for each agent is Porelo's.
- A task over several issues takes the highest risk among them (`harm` over `ordinary` over `small`), so a careful
  choice is never lost to a lighter issue beside it; a discussion has no issue and is `ordinary`.
- Each start names where its choice came from in plain words, for example "Reading, risk harm", beside the agent,
  model and effort.

Steps:

1. **Risk and rules in the store** (M). `Issue.risk`; `StoreReader` and the store's `IssueSchema`/`IssueHead` read
   it; `WorkRule.risks` and `WorkReader` read the new key, a fault in it drops the rule with its words. Red first, in
   `store/git` over local bare repositories: a head with `risk: harm` reads harm, one without reads ordinary, a
   sub-issue reads its parent's, `risk: Harm` is a fault; in `WorkReaderTest`, a rule's `risks` is read and a wrong
   one drops only that rule. Harm prevented: an issue that can do harm read as ordinary, or a bad rule taking others
   down.
2. **The choice in the core** (S). One small `Choice` (agent, model, effort, words) made from a rule and the task's
   issues, used by `WorkProposal.propose`, `NextStep.task`, `IdeaDiscussion`, and by `WorkOrder.offered` for what may
   be chosen. Red first, unit, in `NextStepTest`: a Reading task for a `harm` issue carries the rule's effort for
   `harm`; in `WorkProposalTest`, a story of a `small` and a `harm` issue in one repository is proposed with `harm`'s
   model. Harm prevented: work that can do harm read with a light model or effort.
3. **The contract and the mock board** (M). Written first: `PlanningStartTask` gets `effort` (absent when no rule
   names one) and `chosenBy`; `PlanWorker` and `PlanAssignment` get `effort` and `chosenBy`; `PlanWorkTask` gets
   `effort`. `BoardController` and `WorkAnswers` serve them; the mock board's rules (`MockStore`) name an effort for
   `harm`. Red first, in `app`: a served start for a `harm` issue carries the effort, a chosen effort the rule does not
   offer is refused. Harm prevented: the window starting a different effort than the rule set.
4. **The screen** (S). `PlanningTaskOrder` reads `effort` and `chosenBy` and hands them to the window's hook; the start
   dialog shows both beside the model; the screen sends `PlanWorker` back with them, since it allows no unknown key.
   Red first, in `screen`: an order without `effort` reaches the hook without one, one with it keeps it. Harm
   prevented: an effort dropped between the planner and the window.

Proposed split: one issue for steps 1 and 2 (M + S = 100 minutes, an L), the planner choosing by risk; one for steps
3 and 4 (M + S = 100 minutes, an L), handing the choice to the window, which waits until the window's Start takes an
effort. Both build after the worker field of [PL46](README.md#pl46), which changes the same head reading, schema copy
and `Issue`.

Needs from other repositories, in words:

- The build tools' issue schema and head take `risk` with the values `harm`, `ordinary` and `small`, written in a
  fixed place after the other optional fields, so `issues check` accepts it and the index writer keeps it. Until then
  the planner reads it but no head may carry it.
- The window hands an effort from the planning hook to Porelo's Start, and Start takes an effort and maps it for each
  agent. If either is missing, the window starts as today and the served `effort` is not used.
- A project's `stories/work.yml` takes `risks` only after the planner that reads it ships, since today's planner drops
  a rule with an unknown key.

## Acceptance

- With a work rule that names an effort for `harm`: a Reading started by itself for a `harm` issue reaches the window's
  hook with that rule's model and effort and the words "Reading, risk harm"; the same issue without `risk` gets the
  `ordinary` choice; a project whose rules name no `risks` starts as today. Each test named above is seen red first.

## Open questions

- May the person change the effort in the start dialog, as they may choose among the rule's models today, or does the
  rule's effort stand?

## To be checked

- What the window's Start takes for an effort today, its name and values, and whether Porelo maps one effort scale
  per agent: not visible in this repository's contract or screen package, so asked of the window's agent.
