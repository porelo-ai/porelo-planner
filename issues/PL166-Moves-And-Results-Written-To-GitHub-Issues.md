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
  "covers": "The GitHub store saves a move, a worker, a priority, a new issue, an idea and a task's stage result with the person's own GitHub access, without a commit: field changes bundled into one GraphQL mutation, a stale board refused, a partly saved batch named, and a stage result and its footprint written into the issue's fixed block and as a comment under the person's name"
}
```

**Why this size.** M: the five write methods of `PlanningStore` in `store/github`, each with its refusals, over the
reading of [PL165](README.md#pl165); no contract with the window changes, since the window calls the same operations.

**What must be true.** A person who moves an issue sees it moved on GitHub at once, and a finished task's result
stands on its issue under the person's name, with no fetch, commit or push.

## Plan

- Today `GitStore.save`, `createIssue`, `createIdea` and `saveResult` write files, index them, commit and push,
  refusing with `StaleRevision`, `NotSavable`, `Busy` and `PartlySaved` (`PlanningStore`).
- `GitHubStore.save(revision, writes)`: re-reads the `updatedAt` of the items written; a change since `revision`
  is `StaleRevision`. The states, workers and priorities become `updateProjectV2ItemFieldValue` calls in one GraphQL
  mutation (the story measured ten issues in three repositories at 6.3 s); a closed state also closes the issue.
  GitHub has no transaction: what failed after what succeeded is `PartlySaved` with the ids saved.
- `createIssue`: issue, sub-issue link, project item and fields (four calls, measured 4.6 s), its id as [PL164](README.md#pl164)'s
  answer gives it (the next number of its prefix). Two persons adding at once may count the same number: after the
  write the planner reads the prefix's ids again, and of two items with one `Id` the one with the higher GitHub
  number takes the next free one. `createIdea`: the story and its issues the same way, bundled.
- `saveResult(result)`: the plan, why this size, to be checked and the stage's footprint into the fixed block of each issue's body, the new
  state into `Status`, and one comment with the result, written with the person's token so GitHub shows the person
  as its author; findings become new issues as today.
- No stage record is written and no measured-state rule is checked: the stage's footprint is added to the fixed
  block beside its analysis result, in the same body edit, and the number fields `Estimate`, `Minutes`,
  `VM minutes`, `Tokens` and `Stages` are set to the block's new sums in the same field mutation as `Status`.
- Red first: `GitHubStoreSaveTest` over recorded answers: a move of two issues in two repositories ends in one
  mutation; a board changed meanwhile is refused; the second of two calls failing gives `PartlySaved`; a stage
  result leaves the person's text above the fixed block byte for byte.

## Acceptance

- In the test organization a move made in the window shows on GitHub within the second after the answer, and no
  repository gains a commit.
- A finished task's result shows on its issue as a comment by the person.

## To be checked

- Whether one GraphQL mutation can carry field updates for items of several repositories and how GitHub answers a
  partial failure in it.
- With Agent Gorge: the mutations for issues, sub-issues, dependencies, field values and comments in `GitHubGraphQl`.
