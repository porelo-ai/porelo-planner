# PL142 — The Git Store Writes A Stage Result

```json
{
  "$schema": "issue-1.json",
  "id": "PL142",
  "title": "The Git Store Writes A Stage Result",
  "state": "Backlog",
  "size": "M",
  "priority": "High",
  "rank": 4,
  "blockedBy": [],
  "covers": "The store's interface takes an analysis's or an estimate's result for an issue and saves it with the issue's new state in one save; the Git store writes the plan, size and to-be-checked into the issue's file and head at the central repository's tip and the index with it, so no agent writes the Git store's format"
}
```

**Size:** M - one member of the store's interface, the Git store's write of an issue's sections, the mock store and
their unit tests over local bare repositories.

**What must be true.** A plan or an estimate a task handed back stands in the issue as the store keeps it - in the Git
store, its file on the central repository - whatever copy of the repository the task worked on.

## Plan

- Today `PlanningStore` writes states (`save` with `Writes`), new issues and ideas; nothing writes an issue's text. An
  agent edits `issues/<id>-*.md` in its own checkout, so the store's format is written outside the store and a store
  that is not Git could never be planned in.
- `store/api`: `PlanningStore.saveResult(Revision, StageResult)` - each issue's plan text, size, estimate, to-be-checked
  and findings, and the state it moves to - defaulting to unsupported like the other writes. `MockStore` keeps it in
  memory.
- `GitStore`: one commit per repository, built on the planner's own clone at the fetched tip as every save is: the
  issue head's `size` and `state`, the *Size* line, the *Plan* section replaced by the plan text, *To be checked*
  replaced by its list or removed when empty; other sections and the head's other members stay byte for byte. The
  index is written as the planner's own `issues index` writes it, and the commit is read back before it stands, as
  every save is. An issue whose file is gone, or whose head the save would make invalid by the work format's check,
  is refused and nothing is written.
- Red first, in `GitStoreSaveTest`: a result rewrites exactly the Plan, Size and To be checked of the issue and its
  index row, and leaves the description and a sibling issue untouched; a result for an issue the central repository
  changed meanwhile is written on the new tip; one that would fail the work format's check writes nothing. Harm: an
  issue's text lost or garbled by a save nobody reviews.

## Acceptance

- The tests above, seen red; the Git store's suite passes; a saved result reads back through `issues check` clean.
