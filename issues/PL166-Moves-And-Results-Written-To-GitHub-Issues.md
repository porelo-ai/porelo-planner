# PL166 — Moves and Results Written to GitHub Issues

```json
{
  "$schema": "issue-1.json",
  "id": "PL166",
  "title": "Moves and Results Written to GitHub Issues",
  "state": "Backlog",
  "size": "M",
  "priority": "High",
  "rank": 82,
  "blockedBy": ["PL165"],
  "covers": "The GitHub store saves a move, a worker, a priority, a new issue, an idea and a task's stage result with the person's own GitHub access, without a commit: field changes bundled into one GraphQL mutation, a stale board refused, a partly saved batch named, and a stage result written into the sections of the issue's body it names and as one stage comment under the person's name"
}
```

**Why this size.** M, though the forge clients bring the batched mutations: five write methods of `PlanningStore`,
each with its refusals, the stale check and a partly saved batch; no contract with the window changes, since the
window calls the same operations. No id is counted: a new item is known by its GitHub number.

**What must be true.** A person who moves an issue sees it moved on GitHub at once, and a finished task's result
stands on its issue under the person's name, with no fetch, commit or push.

## Plan

- Today `GitStore.save`, `createIssue`, `createIdea` and `saveResult` write files, index them, commit and push,
  refusing with `StaleRevision`, `NotSavable`, `Busy` and `PartlySaved` (`PlanningStore`).
- `FieldsBoardWriter.save(revision, writes)`: right before the write it reads the items to be written by
  `issueBoard` and compares each one's digest with the snapshot the request was based on. The digest covers its
  field values, `Status` included, its parent, blockers, open state and body. A change by a person in between is
  `StaleRevision` naming the item, and nothing is written. The issue's `updatedAt` alone is not enough, since a
  project field changed by hand leaves it as it was. GitHub takes no condition on a write (no `If-Match` on the
  issue update or the field mutation), so a change in the second between this read and the write is not caught.
  That window is the one left, and it is named in the user page. The states, workers and priorities go through the forge clients' `setFieldValues`, one aliased
  mutation of at most 50 values (the story measured ten issues in three repositories at 6.3 s); a closed state also
  closes the issue through `updateIssue`. GitHub has no transaction: the client answers each alias's result or
  error, and what failed beside what succeeded is `PartlySaved` with the ids saved. The stale check reads the
  written items by `issueBoard`. The store holds no query text and no records of its own.
- `createIssue`: `createIssues`, `addProjectItems` and `setFieldValues` (measured 4.6 s for the four calls of a
  single issue); a new issue is known by its tracker reference, GitHub's own number, and its `Id` stays empty, so
  two persons adding at once never collide. `createIdea`: the story and its issues the same way, bundled. Both
  answer the tracker reference only once [PL170](README.md#pl170) makes it an id of the window's contract; until
  then they are refused as not offered.
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

## Order and files

Built in this order, each pushed once what it needs is published: [PL164](README.md#pl164)'s types;
[PL169](README.md#pl169)'s contract (independent, at once); then [PL165](README.md#pl165), [PL125](README.md#pl125)
followed by [PL166](README.md#pl166), and [PL169](README.md#pl169)'s behavior followed by [PL167](README.md#pl167),
side by side; then [PL168](README.md#pl168). Each builds against the forge clients' published surface with fakes of
its own.

- Owns `store/github`: `FieldsBoardWriter` (implements `BoardWriter`). Test: `FieldsBoardWriterTest`. Built against
  [PL164](README.md#pl164)'s types and a fake reader, so it does not wait on [PL165](README.md#pl165).

## Acceptance

- In the test organization a move made in the window shows on GitHub within the second after the answer, and no
  repository gains a commit.
- A finished task's result shows on its issue as a comment by the person.

## To be checked

- Measured by the forge clients' recorder: GitHub saves the parts before and after a refused one, and a batch that
  fails naming no part is unknown, so the store reads the written items by `issueBoard` before it answers or retries;
  the `PartlySaved` answer names what GitHub really saved.
- Measured: a project's status changes list only an item's first `Status`, not a later one written through the API.
  Whether a move written by the planner shows in GitHub's timeline later is still being measured; if it does not,
  a move's course is not kept on GitHub, and the plan's claim that the timeline shows each move is narrowed to
  moves a person makes on GitHub.
