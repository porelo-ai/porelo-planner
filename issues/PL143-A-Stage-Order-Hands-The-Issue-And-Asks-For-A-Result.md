# PL143 — A Stage Order Hands The Issue And Asks For A Result

```json
{
  "$schema": "issue-1.json",
  "id": "PL143",
  "title": "A Stage Order Hands The Issue And Asks For A Result",
  "state": "Review",
  "size": "M",
  "priority": "High",
  "rank": 5,
  "blockedBy": [],
  "covers": "The order of an analysis or estimation task carries each issue's current text as the store holds it, as a given file the task reads, names the result file, and says to write that file and nothing else: no edit under issues/, no commit, no push"
}
```

**Size:** M - a work rule's new member read from the store, the task's two members in the contract the window reads,
the work record's migration and the core's order building; grew from S once the text became given files.

**What must be true.** An agent analyzing or estimating an issue reads the issue as the store holds it now and knows
that its work is the result file alone, not an edit of the repository it was given.

## Plan

- Today `WorkOrder` fills the rule's `order` with `{story}`, `{issues}` and `{tasks}`; the agent reads the issue from
  its own checkout of the repository, which can be older than the central repository's tip. `PlanningStartTask` has a
  `result` member, a plain file name, used by the discussion alone.
- `store/api` `WorkRule` gains `result` (`ANALYSIS` with the file `analysis.json`, `ESTIMATE` with `estimate.json`,
  or null for work that pushes); the Git store's `WorkReader` reads `result:` of a rule in the work rules and refuses
  any other value. The project's own work rules name it for the analysis and estimation states once this is served.
- `WorkOrder.Task` gains `result` (the file name, or null) and `given` (one file per issue the task owns, `<id>.md`,
  holding `Issue.text` as the board read at the order's revision gives it). Every path that builds a task - an order
  started by hand (`WorkOrder.accept`), a step the planner takes by itself (`NextStep`) and the run's copies
  (`WorkRun`) - takes both from the rule and the board through one helper, so no path forgets them.
- The prompt of such a task gains, after the rule's own text, the result's instructions: read the issues from the given
  files under Porelo's given directory, write the result to the named file in the format agreed with Porelo and the
  window, edit nothing under `issues/`, commit nothing, push nothing.
- Bounds, fail closed: at most 16 given files; an issue text over 65536 UTF-8 bytes, or the given files together over
  1 MiB, refuse the start with a reason naming the issue, rather than a task that reads half an issue.
- Contract: `PlanningStartTask.result` may be present for MAIN of a result stage too; `given` is a new optional list of
  `{name, text}`, the name `^[A-Z]{1,3}[0-9]{2,3}(\.[0-9]+)?\.md$`. The window passes both to Start unchanged.
- The work record keeps both with each offer; a record written before reads as no result and no given files.
- Red first, in `WorkOrderTest` and `NextStepTest`: an analysis order carries the issue's text as the board gives it,
  the result file and the instructions, a build's order carries neither; an issue text over the bound refuses the
  start. `WorkReaderTest`: `result: ANALYSIS` is read and an unknown value refused. `WorkRecordTest`: an older record
  reads. `ApiMatchesDescriptionTest` holds the members. Harm: an analysis of an outdated issue, a stage task that
  pushes beside its result, and a record that no longer reads.

## Acceptance

- The tests above, seen red; a started analysis task's order on the mock board shows the issue's text and the result's
  instructions.

## To be checked

- How this order's instructions sit with the start instructions per working state the store's adapter gives, planned
  in this repository as a larger change, so the two are not written twice.
- The given directory's path as Porelo mounts it, said in the prompt, confirmed with Porelo's agent.
