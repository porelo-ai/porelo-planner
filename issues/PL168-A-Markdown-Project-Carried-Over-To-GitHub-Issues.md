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
  "covers": "A command of the planner's executable carries a project's Markdown stories and open issues over to GitHub Issues: a dry run lists every issue, field, dependency and rewritten reference it would write; the real run keeps each old id, can be run again without doubling anything, and says what it could not carry"
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
- Only open issues, those with a file, are carried; Finished and Rejected rows of the indexes are not. The highest
  number each prefix ever used, its rows included, is written into `project.yml` as that prefix's `issue_floor`, so a
  new issue never takes a number already used: a top-level map from prefix to number, which Porelo refuses as an
  unknown key until it knows it, so the real run waits until the Porelo the project runs on accepts `issue_floor`
  (the dry run does not write it). In an organization's project the person signs the changed file afterwards with
  `porelo project sign`, as for any change to `project.yml`; the run's last line says so.
- An open issue's stage records (`stats/<id>.jsonl`) become its stage comments, oldest first, with their result,
  actor, model, effort, minutes, VM minutes and tokens; its number fields are their sums. GitHub sets each comment's
  `createdAt` to the carry-over's time, so the old ends are not carried. Nothing the board or the velocity reads is
  lost by it: the board reads no time, and the velocity reads `Minutes` by `Size`, both carried. What is lost is
  when each old stage happened; it stays readable in the repository's Git history of `stats/`. Its head moves into
  fields and its sections into the body.
- References in the texts are rewritten: `[PL12](README.md#pl12)` and a link to another repository's index row
  become the GitHub issue's address; a reference to a number not carried (a finished or rejected one) is listed and left as plain text, not guessed.
- The dry run prints one line per item (id, repository, state, fields, dependencies, references rewritten) and
  writes nothing; the real run prints the same lines with the GitHub address and ends non-zero if any item failed.
- Red first: `CarryOverTest` over a fixture project of two repositories with a story, a Porelo sub-issue, a
  dependency and cross links, against a fake GitHub: the dry run's lines, then the writes; a second run writes
  nothing new.

## Acceptance

- In the test organization a copy of a real project is carried over: the dry run's count equals the board's, and
  after the run the planner's board from GitHub equals the board from Markdown for every open issue, id for id and
  field for field, and each prefix's `issue_floor` is its highest number used.

## To be checked

- How long a carry-over of the real project takes against GitHub's secondary rate limits (content-creating calls
  per minute), measured on a copy in the test organization.
