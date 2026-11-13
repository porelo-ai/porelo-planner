# PL37 — A Story's Page With A Folding Description And A Searchable Issue List

```json
{
  "$schema": "issue-1.json",
  "id": "PL37",
  "title": "A Story's Page With A Folding Description And A Searchable Issue List",
  "state": "Review",
  "size": "S",
  "priority": "High",
  "rank": 24,
  "blockedBy": [],
  "covers": "A story's description can be folded and unfolded, and its issue list has the window's list design: a search that opens from a magnifier and a sort menu that flips"
}
```

**Size:** S - one module, the screen package: the story page's description and issue table, with their tests; the
issue list answer already carries every field the list needs, so neither the contract nor the planner changes.

**Estimate:** 33 min, the measured median of S. Uncertain: the same Issues section is changed by the board's
move-at-once and the description's format, so a rebase may cost time.

**What must be true.** On a story's page the description can be folded away and unfolded again, so a long one does
not push the issues out of sight; and the list of its issues looks and works like the window's other lists, with a
search and a sort.

## Plan

Read in today's code: `PlanningStoryView` (`screen/lib/src/ui/planning_story_view.dart`) shows the description as a
`SelectableText` inside `planningSection` (`planning_detail_widgets.dart`), always open, and the issues as a plain
`DataTable` of Issue, Title, Repository, Size, Worker and State, in the order the planner answers, with no search or
sort. The rows come from `PlanIssueList` (read in `PlannerSource`, `screen/lib/src/http/planner_source.dart`, from
`GET .../stories/{story}/issues`); each `PlanIssue` (`models.dart`) carries id, title, repository, state and size,
which is all the list needs. The page's `PlanningDetail` is made anew on each visit (`_PlanningDetailViewState` in
`planning_view.dart`), so a choice kept there would be lost on the way to an issue and back; the board keeps its own
choices per project in `PlanningPreferences` (`planning_board.dart`), which outlives a provider's replacement.

The window's lists all have one shape, which the screen builds with its own widgets and imports nothing of the
window for: a card whose head holds the title and the count, then the search and the sort; rows under it with a line
between them. The search is closed at first: a magnifier button (tooltip "Filter issues") opens a dense field with
the hint "Filter Issues", a search icon before it and a close button ("Close the filter") after it; Escape clears it,
and while closed it hides no row. A row passes when one string of its words - here id, title, repository, state and
size - holds the trimmed text, ignoring case. There is no other filter. The sort is one menu whose face shows an
arrow up or down and the current sort's name; choosing another sort runs ascending, choosing the current one again
flips it, and rows of an equal key keep their given order. With no rows the list says "No issues yet."; with rows
none of which matches, "Nothing matches." and a button "Clear the filter".

Steps:

1. A plain Dart class `PlanningIssueFilter` (new, `screen/lib/src/issue_filter.dart`): the search text, whether it
   is open, and a sort by identifier (its trailing number, as the board orders stories), title, repository, size
   (XS to XL) or state (in the board's reported state order, `PlanningBoard.states`), either way. It returns the
   shown issues, sorted stably, and never changes the list it is given.
2. `PlanningPreferences` keeps, per story, whether its description is folded and its list's search and sort, so they
   survive opening an issue and coming back. A description starts unfolded.
3. `PlanningStoryView` becomes stateful: the Description section's heading gets a fold button; folded, it shows only
   the heading. The Issues section's heading gets the count, the search and the sort, and the table shows the shown
   rows. The progress line ("n of m in Review or Finished") and the pending moves' summary keep counting every issue.

Tests, each with its level and the harm it prevents:

- Unit tests of `PlanningIssueFilter` (Dart, no widgets): the search by each word, trimmed and ignoring case, an
  issue whose fields are not reported, a closed search; each sort, its flip, and its stability. Harm: an issue that
  matches is hidden, so the person misses work that waits on them.
- A widget test in `screen/test/` - the one that goes red first, since today there is no search field: a story of
  three issues, one with a move not yet saved; a search that matches none shows "Nothing matches.", the progress
  line still counts all three, and the pending summary still lists the hidden issue's move. Harm: a story read as
  empty gets its issues filed twice, and a move hidden by a filter is saved unseen.
- The fold itself gets no test: it only reads wrong when it fails.
- Built: `PlanningIssueFilter` with its unit tests, the per-story fold and list choices in `PlanningPreferences`, a
  `heading` row for `planningSection`, and the story page's folding description and searchable, sortable issue list
  with its widget test.
- The code reading's findings, fixed: a refusal of a move of an issue the search hides stands beside the pending
  moves, since its row cannot show it (widget test, seen red first); an unreported or unknown value sorts after the
  reported ones in both directions (unit test, seen red first); a move chosen on the one row a search leaves moves
  that issue alone (widget test, seen red when a row took the issue at its index in the full list); the search field
  takes its text again whenever the page is handed another story's reads, not only another story; the search field
  shrinks in a narrow heading.

## Acceptance

- On a story's page, the description folds to its heading and unfolds again, and stays as it was left after an
  issue is opened and the story is opened again.
- The issue list has a search and a sort in the shape of the window's lists, and a search and a sort each change the
  rows shown; the search text, the sort and its direction stay as they were left after an issue is opened and the
  story is opened again.
- A search that hides every issue says "Nothing matches.", and neither the progress line nor the pending moves
  change with it.
- The new tests are seen red first (no search field; a progress line counting only shown rows), and the screen's
  whole suite and analysis are green.

## To be checked

- The order against the board's move-at-once and the description's own format, which change the same Issues and
  Description sections of `PlanningStoryView`: whichever comes second is rebased onto the first.
