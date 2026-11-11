# PL72 — The Board's Search Finds Issues Too

```json
{
  "$schema": "issue-1.json",
  "id": "PL72",
  "title": "The Board's Search Finds Issues Too",
  "state": "Implementing",
  "size": "S",
  "priority": "High",
  "rank": 1,
  "blockedBy": [],
  "covers": "The board's search matches an issue's number and title too; the story holding a matching issue is shown with its issue list open, the issue marked, and scrolled into view in its column"
}
```

**Size:** S - the board's search and the cards in the screen package and their tests; no contract another repository
reads.

**Estimate:** 33 min, the measured median of S.

**What must be true.** A person who searches the board for an issue's number or words of its title finds it: every story holding a matching issue is shown, its issue list opened with the issue marked, and the story scrolled into view when it lies outside the visible part of its column, as a story found by its own number or title is.

## Plan

Read in today's code: `PlanningBoard._matches` (`screen/lib/src/planning_board.dart`) compares the search with a
story's id and title alone (`_keysOf`, kept per story read). An open issue list is `preferences.expandedStories`,
which the person toggles; `_buildCard` (`screen/lib/src/ui/planning_view.dart`) lists each `PlanIssue` with its id
and title under a `Key('planning-issue-row <id>')`. Every column stands in one vertical `SingleChildScrollView`
(`_vertical`) inside one horizontal one, so a card is brought into view with `Scrollable.ensureVisible`, which walks
both.

Steps:

1. `PlanningBoard` keeps each issue's id and title lower-cased beside the story's keys; a story matches when it or
   one of its issues does, and the board answers the issues that matched the current search (`foundIssues`, empty
   without a search).
2. A card whose story holds a found issue shows its issue list open while the search stands, without touching the
   person's own `expandedStories`, and marks the found issue's row.
3. When the search changes, the view brings the first card with a found issue (the column order of the board) into
   view once, not on every redraw, so scrolling by hand is not taken back.
4. Red first, unit in `planning_board_test.dart`: a search for an issue's number or a word of its title keeps its
   story on the board and answers the issue; a story matching only by its own words opens nothing (harm: work the
   person looks for is hidden). A widget test shows the found issue's row and the card brought into view on a board
   taller than the view.

## Acceptance

- A search for an issue's number or title shows its story with the issue list open and the issue marked, scrolled
  into view; clearing the search leaves the issue lists as the person had them. Seen failing first: today's board
  shows no story for an issue's number.
