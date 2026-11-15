# PL143 — A Stage Order Hands The Issue And Asks For A Result

```json
{
  "$schema": "issue-1.json",
  "id": "PL143",
  "title": "A Stage Order Hands The Issue And Asks For A Result",
  "state": "Backlog",
  "size": "S",
  "priority": "High",
  "rank": 5,
  "blockedBy": [],
  "covers": "The order of an analysis or estimation task carries each issue's current text as the store holds it and says to write the result file and nothing else: no edit under issues/, no commit, no push"
}
```

**Size:** S - the core's building of a task's order and its unit tests; no contract change.

**What must be true.** An agent analyzing or estimating an issue reads the issue as the store holds it now and knows
that its work is the result file alone, not an edit of the repository it was given.

## Plan

- Today `WorkOrder` fills the rule's `order` with `{story}`, `{issues}` and `{tasks}`; the agent reads the issue from
  its own checkout of the repository, which can be older than the central repository's tip.
- For a rule whose stage hands back a result, the order the planner builds gains, after the rule's own text, each
  issue's current text from the store's board read at the order's revision, bounded as a reading's answer is, and the
  result's instructions: the file to write, its format, and that the task edits nothing under `issues/`, commits
  nothing and pushes nothing. The text is quoted as data, fenced so the issue's own text cannot close it.
- Red first, in `WorkOrderTest`: an analysis order holds the issue's text as the board gives it, not a stale copy, and
  the result's instructions; a build's order is as today; an issue text holding the fence is still quoted whole.
  Harm: an analysis of an outdated issue, and a stage task that pushes beside its result.

## Acceptance

- The tests above, seen red; a started analysis task's order on the mock board shows the issue's text and the result's
  instructions.

## To be checked

- How this order's instructions sit with the start instructions per working state the store's adapter gives, planned
  in this repository as a larger change, so the two are not written twice.
