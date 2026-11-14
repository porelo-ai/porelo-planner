# PL133 — The Issue Page Laid Out Anew

```json
{
  "$schema": "issue-1.json",
  "id": "PL133",
  "title": "The Issue Page Laid Out Anew",
  "state": "Backlog",
  "size": "S",
  "priority": "Highest",
  "rank": 7,
  "blockedBy": [],
  "covers": "The issue page has one header - its id, repository, project and title, its state, size and worker as chips in one row with Switch to Manual in the worker's chip, and its revision shortened to seven characters with a copy button - and two columns: what must be true as flowing text and the description as Markdown on the left, one details panel with each named item a link on the right, below the text in a narrow window"
}
```

**Size:** S - the screen package's issue page and its test; no contract.

**What must be true.** The person reads an issue as one page: who and where at the top, the text in the middle, the
links to its story, its waits and its sub-issues beside it.

## Plan

- `screen/lib/src/ui/planning_issue_view.dart` lays out:
  - A header with the id, repository, project and title.
  - Below it, one row of chips: the state (its menu as today), the size, and the worker with "Switch to Manual" inside
    it.
  - The revision, shortened to 7 characters, with a copy button; its tooltip is the full hash.
- Below the header, two columns:
  - Left: "What must be true" as flowing text, with the file's single line breaks joined and paragraphs kept, and then
    the description rendered as Markdown without the leading heading that repeats the id and title.
  - Right: one details panel with the story, blocked by, sub-issues, repository and work. Each item it names is a link.
  - In a narrow window the panel goes under the text.
- The page carries one title, "Planning". The breadcrumb puts a space before its last item.
- No red test for the layout, which is a look. The one harm is the copy: the clipboard gets exactly the full revision,
  and a test that is seen red holds that.

## Acceptance

- The screen's suite passes; the page matches the agreed before/after design in its parts and order.

## To be checked

- Which of the two "Planning" titles is the screen's and which is the window's.
