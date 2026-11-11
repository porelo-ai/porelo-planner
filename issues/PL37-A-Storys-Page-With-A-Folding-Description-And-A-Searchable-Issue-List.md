# PL37 — A Story's Page With A Folding Description And A Searchable Issue List

```json
{
  "$schema": "issue-1.json",
  "id": "PL37",
  "title": "A Story's Page With A Folding Description And A Searchable Issue List",
  "state": "Estimated",
  "size": "S",
  "priority": "Medium",
  "rank": 24,
  "blockedBy": [],
  "covers": "A story's description can be folded and unfolded, and its issue list has the window's list design: a search field, filters and sorting"
}
```

**Size:** S - one module, the screen package: the story page's description and issue table, with their tests; the
issue list answer already carries every field the list needs, so neither the contract nor the planner changes.

**Estimate:** 33 min, the measured median of S. Uncertain: the window's list design is not yet read and may hold
more filters or sort keys than the issue answer carries; the same Issues section is changed by the board's
move-at-once and the description's format, so a rebase may cost time.

**What must be true.** On a story's page the description can be folded away and unfolded again, so a long one does
not push the issues out of sight; and the list of its issues looks and works like the window's other lists, with a
search field, filters and sorting.

## Plan

Read in today's code: `PlanningStoryView` (`screen/lib/src/ui/planning_story_view.dart`) shows the description as a
`SelectableText` inside `planningSection` (`planning_detail_widgets.dart`), always open, and the issues as a plain
`DataTable` of Issue, Title, Repository, Size and State, in the order the planner answers, with no search, filter or
sort. The rows come from `PlanIssueList` (read in `PlannerSource`, `screen/lib/src/http/planner_source.dart`, from
`GET .../stories/{story}/issues`); each `PlanIssue` (`models.dart`) carries id, title, repository, state and size,
which is all the list needs. The page's `PlanningDetail` is made anew on each visit (`_PlanningDetailViewState` in
`planning_view.dart`), so a choice kept there would be lost on the way to an issue and back; the board keeps its own
choices per project in `PlanningPreferences` (`planning_board.dart`), which outlives a provider's replacement. The
board's search (`_BoardViewState`: a `TextField` with a search icon, `PlanningStyle.filter` wide) and its
priority dropdown are the screen's own pattern for a search field and a filter.

Steps:

1. A plain Dart class `PlanningIssueFilter` (new, `screen/lib/src/issue_filter.dart`): a search over identifier and
   title, ignoring case; a filter per state, repository and size, built from the values the answer holds; a sort by
   identifier (its trailing number, as the board orders stories), title, repository, size (XS to XL) or state (in
   the board's reported state order, `PlanningBoard.states`), either way. It returns the shown issues and never
   changes the list it is given.
2. `PlanningPreferences` keeps, per story, whether its description is folded and its list's search, filters and sort,
   so they survive opening an issue and coming back. A description starts unfolded.
3. `PlanningStoryView`: the Description section's heading gets a fold button; folded, it shows only the heading. The
   Issues section gets a row of search field, filters and sort above the table, in the board's look, and the table
   shows the filtered rows; a column heading sorts as the sort choice does. The progress line ("n of m in Review or
   Finished") and the pending moves' summary keep counting every issue; when the filters hide every row, the list
   says "No issue matches" with the number hidden, never "No issues yet".

Tests, each with its level and the harm it prevents:

- Unit tests of `PlanningIssueFilter` (Dart, no widgets): search, each filter, each sort and an issue whose fields are
  not reported. Harm: an issue that matches is hidden, so the person misses work that waits on them.
- A widget test in `screen/test/` - the one that goes red first, since today there is no search field: a story of
  three issues, one with a move not yet saved; a search that matches none shows "No issue matches", the progress
  line still counts all three, and the pending summary still lists the hidden issue's move. Harm: a story read as
  empty gets its issues filed twice, and a move hidden by a filter is saved unseen.
- The fold itself gets no test: it only reads wrong when it fails.

## Acceptance

- On a story's page, the description folds to its heading and unfolds again, and stays as it was left after an
  issue is opened and the story is opened again.
- The issue list has a search field, filters and a sort that match the window's lists in look and behavior, and a
  search, a filter and a sort each change the rows shown.
- A filter that hides every issue says so, and neither the progress line nor the pending moves change with it.
- The new tests are seen red first (no search field; a progress line counting only shown rows), and the screen's
  whole suite and analysis are green.

## To be checked

- How the window builds its lists - which widgets, which filters and sort keys - so the screen's list matches them
  without importing anything of the window; asked of the window's agent, since only the issue answer's fields
  (id, title, repository, state, size) can be filtered or sorted on without a contract change.
- Whether the worker field, built in another branch, has landed by then; if so, it becomes one more filter.
- The order against the board's move-at-once and the description's own format, which change the same Issues and
  Description sections of `PlanningStoryView`: whichever comes second is rebased onto the first.
