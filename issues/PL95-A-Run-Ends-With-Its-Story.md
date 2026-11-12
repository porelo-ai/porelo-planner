# PL95 — A Run Ends With Its Story

```json
{
  "$schema": "issue-1.json",
  "id": "PL95",
  "title": "A Run Ends With Its Story",
  "state": "Review",
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
- **No run outlives its story into a new one.** Before a handed idea is saved, `HandedIdeas` drops every run whose
  story is no longer on the board and that has no task at work (`WorkRun.withoutGoneRuns`), in the same write that
  records the save as pending, so a stop between the store's save and the record's write keeps no such run. A story
  saved under one of those IDs then starts with no run. The record holds one run per story ID, so a gone story's run
  with a task still at work cannot stand beside a new one: the hand-in is refused (`WorkRunning`, naming the old story
  and its tasks) and nothing is saved, until the person stops that task, which stays stoppable. Runs of stories still
  on the board are untouched.
- **Tests, red first.** Unit, core (`HandedIdeasTest`): a gone story's ended run is dropped and the new story's work
  reads as none; it is dropped even when the save then fails; a hand-in is refused while a gone story's task works,
  with nothing saved and the record unchanged; a run of a story still on the board is kept. Harm: a new story blocked
  by an old run, an old run ordering work on a new story, a task left unstoppable, a shown story's work lost. The app
  passes the hand-in to `WorkRun.idea` unchanged, so no HTTP test repeats it.

## Acceptance

- Run 27's case passes without a hand repair: a story saved anew under an old ID shows no work and can be started. Every new test seen red; the suite and the documents' checks pass.

## Not covered

- A story written outside the planner under a reused ID, while the planner never read the board without it, still
  takes over the old run. Closing it needs the story's identity from the store kept with the run; it is left until a
  case is seen.
