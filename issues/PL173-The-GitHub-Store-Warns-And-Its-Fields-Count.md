# PL173 — The GitHub Store Warns Instead of Leaving Out, and Its Fields Count

```json
{
  "$schema": "issue-1.json",
  "id": "PL173",
  "title": "The GitHub Store Warns Instead of Leaving Out, and Its Fields Count",
  "state": "Backlog",
  "size": "M",
  "priority": "High",
  "rank": 90,
  "blockedBy": ["PL164", "PL172"],
  "covers": "The GitHub store maps every hand edit it cannot hold to a warning on the item, takes an issue without Status as an Idea, and shows its fields as GitHub holds them: Status over open or closed, the number fields over the stage comments"
}
```

**Why this size.** M: one module, but every fault of the GitHub mapping changes, with the stage sums and the user
page; it builds on the warnings the store API gives ([PL172](README.md#pl172)).

**What must be true.** Whatever a person edits by hand on GitHub, the item stays on the board, warned where the
planner cannot hold the edit, and what a person set in a field is what the board shows and keeps.

## Plan

- Today [PL164](README.md#pl164)'s `IssueMapping` leaves an item out and names a project fault for:
  - a missing or repeated alias;
  - a `Status` outside the workflow;
  - a parent or blocker outside the project;
  - a sub-issue under another parent than its alias names;
  - a label against its alias;
  - a repository outside the project;
  - a story outside the own repository;
  - a circle of parents;
  - a dependency on an item left out;
  - a sub-issue in another repository than its parent.

  Each becomes a warning on the item through the store API. Only an item without an issue, such as a draft, or an
  item in a repository of another owner stays a project fault.
- An issue without `Status` is an `Idea`, with no warning. A story without `Status` follows its issues, as today. No
  `Priority` is a warning (`NoPriority`) and sorts as Medium.
- **Status over open or closed.** `checkOpen` goes: `Status` counts, and a disagreement is no warning. The planner
  still closes an issue when it writes a closed state (Finished, Rejected) and reopens it when it writes an open
  one, so GitHub's own lists and links agree. It never reads open or closed to decide anything.
- **The number fields over the comments.** The sum check and `Mapped.rewrite` go. The board shows the number fields
  as GitHub holds them, and a value a person overrode stays, with no warning. A stage's save
  ([PL166](README.md#pl166)) adds that stage's minutes, VM minutes, tokens and one stage to the fields as read just
  before the write; it never recomputes them from the comments. So a person's override stays the base that later
  stages add to.
- The stage comments stay the course of the work: one comment per stage, saying who, which model, how long and at
  what cost. They are the record a person reads, and the record a later recount or an audit reads. The board never
  reads them, so a board read costs no call per issue.
- `doc/github-issues.md` says so, replacing its paragraphs on faults and on the comments as the truth.
- Red first: an `IssueMappingTest` case per former fault shows the item kept and warned. Further cases show a closed
  issue in an open `Status`, and a number field apart from its comments, both shown as the fields say with no
  warning. A writer test of [PL166](README.md#pl166) shows a stage adding to a field a person set.

## Acceptance

- On the VM, a board edited by hand into each case shows every item, warned where the case says.
