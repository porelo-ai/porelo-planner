# PL133 — The Issue Page Laid Out Anew

```json
{
  "$schema": "issue-1.json",
  "id": "PL133",
  "title": "The Issue Page Laid Out Anew",
  "state": "Review",
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
  - Below it, one row of chips: the state, the size, and the worker with "Switch to Manual" inside it, then the
    issue's web link.
  - The revision, shortened to 7 characters, with a copy button; its tooltip is the full hash.
- Below the header, two columns:
  - Left: "What must be true" as flowing text, with the file's single line breaks joined and paragraphs kept, and then
    the description rendered as Markdown without the leading heading that repeats the id and title.
  - Right: one details panel with the story, blocked by, sub-issues, repository and work. Each item it names is a link.
  - In a narrow window the panel goes under the text.
- The breadcrumb puts a space before its last item. The detail frame's line "Project ... · From ..." stays on a
  story's page only, since the issue's header names both. A waited-on issue of another story opens under that story.
- One page title: `PlanningScreen` takes an optional `pageButton`, the window's own button for the page, and shows it
  after the board's head and at the end of a story's or an issue's breadcrumb, so the window draws no head of its own
  above the screen. A test seen red holds that the button stands in both places.
- No red test for the layout, which is a look. Two harms are guarded, each by a test seen red: the clipboard gets
  exactly the full revision, and only a first heading naming the issue leaves the description, never another line.

- Read in the code's reading, each fixed with a test seen red:
  - The copy and the label take the cleaned revision.
  - What must be true goes to the text as written, where Markdown makes a wrapped line a space and a code block keeps
    its lines.
  - Only a first Markdown heading that opens with the issue's id leaves the description.
  - A waited-on issue opens under the story the board holds it in.
  - Task names, a task's unavailability, misfit ids and the worker switch's hint are cleaned in tooltips and labels.
- Fixed without a test, since they only read wrong:
  - Empty criteria read "No acceptance criteria."
  - The worker chip and a waited-on issue's row wrap in a narrow panel.

## Acceptance

- The screen's suite passes; the page matches the agreed before/after design in its parts and order.

