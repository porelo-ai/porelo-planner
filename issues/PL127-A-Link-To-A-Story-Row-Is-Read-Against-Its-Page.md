# PL127 — A Link To A Story Row Is Read Against Its Page

```json
{
  "$schema": "issue-1.json",
  "id": "PL127",
  "title": "A Link To A Story Row Is Read Against Its Page",
  "state": "Implementing",
  "size": "S",
  "priority": "High",
  "rank": 1,
  "blockedBy": [],
  "covers": "A link from an issue to a row anchor on a page of its own repository outside issues/, such as the stories index, is read against that page, and a story anchor of one digit is checked as well, so a link that reaches its row is no finding"
}
```

**Size:** S - one class of the Git store and its unit tests; no contract changes.

**What must be true.** A link from an issue to a row of its repository's stories index, such as
`../stories/README.md#st11`, is no finding when that anchor stands on that page, and is one when it does not, for a
story number of one digit too.

## Plan

- Today the reading of issue links fetches a linked page only when it lies under `issues/`; every other page of the
  repository counts as having no anchors, so each link from an issue to a story row is named as reaching none. A
  story anchor of one digit, `#st5`, is not taken for a row anchor at all and so is never checked.
- `IssueLinks` (`planner/store/git/.../stories`): a linked Markdown page anywhere inside the repository is read once
  for its anchors, as a page under `issues/` is now; the path is still refused when it leaves the repository, and
  the store's own path check stays in front of every read. A row anchor's digits are one to three.
- Red first, in `IssueLinksTest`: a link from an issue to `../stories/README.md#st11` and `#st5` that stand there
  passes, and one to `#st12` that does not stand there is named.

## Acceptance

- The unit test above, seen red before the fix: a link to a story row that stands is no finding, one to a row that
  does not stand is, for one digit and two.
