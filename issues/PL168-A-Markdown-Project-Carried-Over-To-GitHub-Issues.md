# PL168 — A Markdown Project Carried Over to GitHub Issues

```json
{
  "$schema": "issue-1.json",
  "id": "PL168",
  "title": "A Markdown Project Carried Over to GitHub Issues",
  "state": "Analyzed",
  "size": "M",
  "priority": "High",
  "rank": 84,
  "blockedBy": ["PL166"],
  "covers": "A command of the planner's executable carries a project's Markdown stories and issues over to GitHub Issues: a dry run lists every issue, field, dependency and rewritten reference it would write; the real run keeps each old id, can be run again without doubling anything, and says what it could not carry"
}
```

**Why this size.** M: one command in the executable over the Git store's reading and the GitHub store's writing,
the rewriting of references, and its tests; the removal of the Markdown files is each repository's own work.

**What must be true.** On the cut-over day a project's planning moves to GitHub Issues in one run that was seen
first as a dry run, and every story and issue keeps the id people know it by.

## Plan

- Today the executable's `issues` and `stories` commands read the Markdown store through `store/git` (`IssueIndex`,
  `StoriesIndex`, `IssueLinks`); the Git store's `refresh()` gives every story and issue with its text.
- `porelo-planner carry-over --project <dir> --to <owner>/<number> [--dry-run]`: reads the board from the
  checkouts, writes through `GitHubStore` ([PL166](README.md#pl166)) with [PL164](README.md#pl164)'s mapping, the old id into `Id`. A story or issue whose
  `Id` is already on the GitHub project is updated, never made again, so a run that stopped can run again.
- References in the texts are rewritten: `[PL12](README.md#pl12)` and a link to another repository's index row
  become the GitHub issue's address; a reference to a number not carried is listed, not guessed.
- The dry run prints one line per item (id, repository, state, fields, dependencies, references rewritten) and
  writes nothing; the real run prints the same lines with the GitHub address and ends non-zero if any item failed.
- Red first: `CarryOverTest` over a fixture project of two repositories with a story, a Porelo sub-issue, a
  dependency and cross links, against a fake GitHub: the dry run's lines, then the writes; a second run writes
  nothing new.

## Acceptance

- In the test organization a copy of a real project is carried over: the dry run's count equals the board's, and
  after the run the planner's board from GitHub equals the board from Markdown, id for id and field for field.

## Open questions

- Whether Finished and Rejected rows that have no file left (their one sentence in the index) are carried as closed
  GitHub issues, so their numbers stay findable, or are left behind in the old indexes' history.

## To be checked

- How long a carry-over of the real project takes against GitHub's secondary rate limits (content-creating calls
  per minute), measured on a copy in the test organization.
