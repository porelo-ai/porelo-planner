# PL95 — A Run Ends With Its Story

```json
{
  "$schema": "issue-1.json",
  "id": "PL95",
  "title": "A Run Ends With Its Story",
  "state": "Implementing",
  "size": "M",
  "priority": "Highest",
  "rank": 1,
  "blockedBy": [],
  "covers": "The planner's local record of a story's run ends with the story: a run whose story is gone from the store is dropped, and a story saved anew under an old ID starts without the run recorded before it"
}
```

**Size:** M - the run record's rules in `core` (`WorkRun`, `HandedIdeas`, the proposal's refusal) and their reading in
`app` (`WorkRecord`), with unit tests over fakes and one HTTP test; no contract change.

**Estimate:** 67 min, the measured median of M.

**What must be true.** The planner keeps each story's run in its own local work record, apart from the store. When the
story's file leaves the store (a reset, a deletion, a story rejected and removed), its run must not outlive it. A new
story that is saved under the same ID, with issues under the same IDs, must start with no run. Today the old run is
taken over: the new story shows the old tasks and "The run ended", and "Start work" is refused because the run needs
attention, until someone removes the record by hand.

## Plan

- **Today, read in the code.** `WorkRun.Snapshot` keeps `runs` by story ID and the handed ideas (`HandedIdeas.Handed`,
  with the story each saved) in one record, written under one lock (`WorkRecord`). A run is replaced only by a new order
  for the same story (`WorkRun.replace`) and never removed. `WorkRun.work`, the board's decoration and
  `WorkRun.propose` read `runs.get(story)` without asking whether that story is still the one the run was for; a run
  that needs attention refuses the proposal. `stop` keeps a running story stoppable even when it left the board.
- **A story saved anew starts with no run.** When `HandedIdeas` records a handed idea as saved under story S, the same
  new generation drops a run of S that has no task at work. A run of S with a task still at work is kept stoppable, but
  it is no longer S's: its offers are voided, it is marked halted, and it is dropped once its last task reports its
  end. The new story then shows no work and can be started.
- **A run whose story is gone is not shown or obeyed.** `work`, the board's decoration and `propose` ignore a run
  whose story is not on the board read under the same lock and that has no task at work; the next write of the record
  drops it. A run with a task at work stays, as `stop` needs it.
- **Tests, red first.**
  - Unit, core (`HandedIdeasTest`, `WorkRunTest`): an ended run of ST1 is dropped when an idea is saved as ST1, and
    the proposal for the new ST1 is not refused; a run with a task at work is halted, its offers voided, still
    stoppable, and dropped after its last end; a run whose story left the board is neither shown nor refusing, and is
    gone after the next write; a run of a story still on the board is untouched. Harm: a new story blocked by an old
    run, or an old task's end judged against a new story's issues.
  - Integration, app (`WorkRunHttpTest`): run 27's case over HTTP: a run ended for ST1, the store reset, an idea
    handed in as ST1, then the story's work is empty and the proposal answers.

## Acceptance

- Run 27's case passes without a hand repair: a story saved anew under an old ID shows no work and its proposal
  answers. Every new test seen red; the suite and the documents' checks pass.

## Not covered

- A story written outside the planner under a reused ID, while the planner never read the board without it, still
  takes over the old run. Closing it needs the story's identity from the store kept with the run; it is left until a
  case is seen.
