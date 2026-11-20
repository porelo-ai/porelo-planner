# PL175 — Issues Made Outside the Project Offered for Adding

```json
{
  "$schema": "issue-1.json",
  "id": "PL175",
  "title": "Issues Made Outside the Project Offered for Adding",
  "state": "Analyzed",
  "size": "S",
  "priority": "Low",
  "rank": 92,
  "blockedBy": ["PL165"],
  "covers": "An open issue a person made by hand in one of the project's repositories without adding it to the GitHub project is found and offered for adding to the board"
}
```

**Why this size.** S: one more read per repository in the GitHub store's refresh, and one offer in the screen
over an existing write (`addProjectItems`).

**What must be true.** An issue a person opened in a project's repository without putting it on the project is not
forgotten: the board offers to add it.

## Plan

- GitHub can find such an issue cheaply, and the forge clients already have the call (`issuesIfChanged`). Per
  repository, the REST issue list (open issues, by update time) is read with its ETag, and an answer of 304 does
  not lower the rate limit (measured by the forge clients' owner). A
  project of ten repositories costs ten calls per refresh, nearly all 304. Search (`repo:<r> is:issue is:open
  no:project`) would be one call, but it has a rate limit of its own (30 calls a minute) and no ETag, so it is not
  used.
- An open issue in the list whose node id is no item of the project is named in the board's answer as
  `outside`: its repository, number and title, so the person can recognize it. The screen offers to add it, and
  the add is `addProjectItems` with the issue's `Status` left empty, so it shows as an `Idea`.
- A pull request in the list is skipped, since GitHub's issue list holds pull requests too. At most 100 per
  repository are offered, the newest first.
- Red first: a test over a fake client: an issue with no project item is offered, and an item on the project and
  a pull request are not.

## Acceptance

- In the test organization, an issue opened by hand is offered on the board and added by one click.
