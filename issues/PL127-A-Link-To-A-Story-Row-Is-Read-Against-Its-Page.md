# PL127 — A Link To A Story Row Is Read Against Its Page

```json
{
  "$schema": "issue-1.json",
  "id": "PL127",
  "title": "A Link To A Story Row Is Read Against Its Page",
  "state": "Review",
  "size": "S",
  "priority": "Highest",
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
- `IssueLinks` (`planner/store/git/.../stories`): a linked Markdown page under `issues/` or `stories/` is read once
  for its anchors; both lie in the commit's planning files the reading already holds, so a link costs no Git run of
  its own, and a page elsewhere is never fetched, however many an issue links. The store's own path check stays in
  front of every read. A row anchor is an issue number's shape or a story's, `st` and one to five digits, so a
  heading's anchor of one digit such as `#v2` is not taken for a row.
- Red first, in `IssueLinksTest`: a link from an issue to `../stories/README.md#st11` and `#st5` that stand there
  passes, and one to `#st12` that does not stand there is named; a page outside both folders is not fetched; `#h1`
  and `#v2` are no row anchors.
- Declined: a story link of one digit whose text names another story than its anchor, `[ST5](...#st11)`, is not
  named, while one of two or three digits is, through the issue number's shape. A wrong text is a wording fault and
  does no harm, and naming it would add findings for links to a story's own file.

## Acceptance

- The unit test above, seen red before the fix: a link to a story row that stands is no finding, one to a row that
  does not stand is, for one digit and two; no page outside `issues/` and `stories/` is fetched.
