# PL166 — Moves and Results Written to GitHub Issues

```json
{
  "$schema": "issue-1.json",
  "id": "PL166",
  "title": "Moves and Results Written to GitHub Issues",
  "state": "Analyzed",
  "size": "M",
  "priority": "High",
  "rank": 82,
  "blockedBy": ["PL165"],
  "covers": "The GitHub store saves a move, a worker, a priority, a new issue, an idea and a task's stage result with the person's own GitHub access, without a commit: field changes bundled into one GraphQL mutation, a stale board refused, a partly saved batch named, and a stage result written into the sections of the issue's body it names and as one stage comment under the person's name"
}
```

**Why this size.** M, though the forge clients bring the batched mutations: five write methods of `PlanningStore`,
each with its refusals, the stale check, a partly saved batch and the id race between two persons; no contract with
the window changes, since the window calls the same operations.

**What must be true.** A person who moves an issue sees it moved on GitHub at once, and a finished task's result
stands on its issue under the person's name, with no fetch, commit or push.

## Plan

- Today `GitStore.save`, `createIssue`, `createIdea` and `saveResult` write files, index them, commit and push,
  refusing with `StaleRevision`, `NotSavable`, `Busy` and `PartlySaved` (`PlanningStore`).
- `GitHubStore.save(revision, writes)`: re-reads the `updatedAt` of the items written; a change since `revision`
  is `StaleRevision`. The states, workers and priorities go through the forge clients' `setFieldValues`, one aliased
  mutation of at most 50 values (the story measured ten issues in three repositories at 6.3 s); a closed state also
  closes the issue through `updateIssue`. GitHub has no transaction: the client answers each alias's result or
  error, and what failed beside what succeeded is `PartlySaved` with the ids saved. The stale check reads the
  written items by `issueBoard`. The store holds no query text and no records of its own.
- `createIssue`: `createIssues`, `addProjectItems` and `setFieldValues` (measured 4.6 s for the four calls of a
  single issue), its id as [PL164](README.md#pl164)'s
  answer gives it (the next number of its prefix). Two persons adding at once may count the same number: after the
  write the planner reads the prefix's ids again, and of two items with one `Id` the one with the higher GitHub
  number takes the next free one. `createIdea`: the story and its issues the same way, bundled.
- `saveResult(result)`: the plan, why this size and to be checked overwrite those sections of each issue's body,
  leaving the others; the new state goes into `Status`; and one stage comment, in the form the mapping gives
  (result, actor, model, effort, minutes, VM minutes, tokens from Porelo's record of the task), is written with
  the person's token so GitHub shows the person as its author. Its `createdAt` is the stage's end. Findings become new issues as today.
- No stage record is written and no measured-state rule is checked. The number fields `Estimate`, `Minutes`,
  `VM minutes`, `Tokens` and `Stages` are set to the new sums of the stage comments in the same field mutation as
  `Status`; the sums come from the board's fields plus the new stage, so no comment is read back to save.
- Red first: `GitHubStoreSaveTest` over recorded answers: a move of two issues in two repositories ends in one
  mutation; a board changed meanwhile is refused; the second of two calls failing gives `PartlySaved`; a stage
  result that names *Plan* leaves *Acceptance* byte for byte and adds exactly one stage comment.

## Acceptance

- In the test organization a move made in the window shows on GitHub within the second after the answer, and no
  repository gains a commit.
- A finished task's result shows on its issue as a comment by the person.

## To be checked

- Whether GitHub applies the aliases before and after a failing one (the forge clients' recorder measures it); the
  `PartlySaved` answer names what GitHub really saved.
