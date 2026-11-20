# PL189 — An Item Just Added Not Read As Outside The Project

```json
{
  "$schema": "issue-1.json",
  "id": "PL189",
  "title": "An Item Just Added Not Read As Outside The Project",
  "state": "Review",
  "size": "S",
  "priority": "High",
  "rank": 2,
  "blockedBy": [],
  "covers": "A board read right after an issue was added to the tracked project does not count that issue as a sub-issue outside the project while GitHub's item list lags: the reader confirms the issue's own project items before naming the fault, and the joint run waits until the project lists every item it added"
}
```

**Why this size.** S: one reader of the GitHub store asks one more query for the sub-issues it cannot place, with its
unit tests over the fake; the acceptance run's setup waits on the same list; no contract.

**What must be true.** A person who adds an issue to the project and opens the board at once sees it in its story,
not a fault naming the story; an issue really outside the project is still named.

## Plan

- Seen once in the joint acceptance run against porelo-ai-test, then not in the next run on the same build: the
  first board read after setup named `<story> has a sub-issue outside the project` for an issue the setup had added
  to the project seconds before. `addProjectV2ItemById` had answered the item's id; the setup checked only that
  answer, never the project's item list.
- Read in the code: `ItemsBoardReader` builds the board from the project's item list
  (`GitHubGraphQl.projectItems`), and a story's sub-issues from the issue's own `subIssues`. `IssueMapping.story`
  names the fault for a sub-issue whose node id the item list does not hold. The read is right on what it reads: the
  two GitHub lists disagreed for a moment, which is inferred to be GitHub's item list lagging an add, not measured.
- Built: `IssueMapping` answers, beside its faults, the node ids of the sub-issues it named outside the project.
  `ItemsBoardReader` keeps those the item list does not hold at all (a listed sub-issue of a repository the project
  does not plan is no lag) and reads their own project items in one `issueBoard` query per hundred, through
  `ProjectQueries`, which `GitHubPace` watches as it watches the other reads. When one of them names the project, the
  item list is read again after a pause of 1 s, at most 3 times, while it still lacks one of those; the fault is then
  named as before if it still does. A sub-issue whose project items name no project is named at once, with no read
  again. A board whose stories' sub-issues are all listed makes no extra call. One INFO line says how many sub-issues
  lagged and how often the list was read, with no issue text and no token.
- The query and its answer records are the forge client's existing `issueBoard`, which builds its records from the
  JSON tree without reflection, so no native-image registration is added.
- The joint run's setup waits until the project's item list holds every item it added, before the planner starts;
  that change lies in the acceptance scripts outside this repository.
- Red first, `ItemsBoardReaderTest` over the fake: a sub-issue missing from the first item list and present in the
  second, with its project items naming the project, reads without a fault after one more read; one whose project
  items name no project reads with the fault and no second read; one still missing after the bound reads with the
  fault; a board with every sub-issue listed, and one whose sub-issue is listed in a repository the project does not
  plan, make no `issueBoard` call. Harm: a board shown with a story missing an issue, or a fault the person cannot act
  on, after their own add; and the person's rate limit spent on extra reads.

## Acceptance

- The unit tests above, seen red against today's reader; the joint run's step 1 green in three runs in a row.
