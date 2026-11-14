# PL134 — A Sub-Issue Names Its Parent

```json
{
  "$schema": "issue-1.json",
  "id": "PL134",
  "title": "A Sub-Issue Names Its Parent",
  "state": "Review",
  "size": "XS",
  "priority": "Highest",
  "rank": 8,
  "blockedBy": [],
  "covers": "On a sub-issue's page the details panel shows Parent with the parent's id as a link to its page and no Sub-issues row, since a sub-issue has none; a top-level issue keeps its Sub-issues row"
}
```

**Size:** XS - one row of the screen's issue page and its test; no contract.

**What must be true.** On a sub-issue's page the person sees its parent and opens it with one click, instead of a
Sub-issues row that can never hold anything.

## Plan

- `screen/lib/src/ui/planning_issue_view.dart`, `_details`: when the issue has a parent, the panel's row is "Parent"
  with the parent's id as a link (`planning-parent <id>`) that opens the parent under the story the board holds it in,
  and there is no "Sub-issues" row; an issue without a parent keeps "Sub-issues" as it is. An issue id has one dot at
  most and a parent is a top-level id, so a sub-issue never has sub-issues.
- Red first: a screen test in `screen/test/planning_issue_page_test.dart` that a sub-issue's page shows the Parent
  link, opens the parent, and shows no Sub-issues row, while a top-level issue's page shows Sub-issues and no Parent.

## Acceptance

- The screen's issue page test passes and was seen red against today's panel.
