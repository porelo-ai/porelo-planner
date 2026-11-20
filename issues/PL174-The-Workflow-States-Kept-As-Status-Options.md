# PL174 — The Workflow's States Kept as Status Options Without Losing a Value

```json
{
  "$schema": "issue-1.json",
  "id": "PL174",
  "title": "The Workflow's States Kept as Status Options Without Losing a Value",
  "state": "Backlog",
  "size": "S",
  "priority": "High",
  "rank": 91,
  "blockedBy": ["PL166"],
  "covers": "The GitHub project's Status options follow the workflow's states only through one planner step that writes every item's value back, so no item loses its Status when the options change"
}
```

**Why this size.** S: one write step in the GitHub store, over calls the forge clients have, and its tests.

**What must be true.** No item ever loses its `Status` because the workflow or the field's options changed. An item
whose `Status` is no state of the workflow is shown and warned, and the person can move it.

## Plan

- A `Status` outside the workflow arises in three ways:
  - a person adds an option to the field on GitHub and sets it;
  - the workflow renames or removes a state while items still hold the old option, since the field keeps its
    options until the planner changes them;
  - the planner changes the options itself. Measured by the forge clients' owner: `setFieldOptions` gives every
    option a new id, even one whose name stays, and clears every item's value in that field. By the rule that an
    issue without `Status` is an `Idea` ([PL173](README.md#pl173)), every issue would fall back to `Idea`.
- So the options change only in one step of the planner's own: read every item's `Status` by name, set the
  options, then write every value back by its new option id in batches. A value whose state is gone is written
  back as an option of its own name, kept at the end and warned ([PL172](README.md#pl172)), never dropped. The step
  is idempotent, so after a failed batch it runs again from a fresh read. It runs at the carry-over and when the
  person confirms it after the workflow changed. It never runs on a read.
- Red first: a test over a fake client whose `setFieldOptions` clears every value: after the step every item holds
  its state by name, and a removed state is kept and warned.

### Plan reading

- An option the workflow does not have is never dropped by the step, held or not: an item the step names to be set
  by hand may need it, and an option a person added is theirs. The step cannot tell a person's option from a state
  the workflow dropped, so a person removes either on GitHub.
- The step has no caller yet; the carry-over and the person's confirmation call it: no change here.

## Acceptance

- In the test organization, a workflow with a renamed state is applied to a project of ten items, and every item
  keeps its state.
