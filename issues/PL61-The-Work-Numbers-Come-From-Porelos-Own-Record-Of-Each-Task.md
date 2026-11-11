# PL61 — The Work Numbers Come From Porelo's Own Record Of Each Task

```json
{
  "$schema": "issue-1.json",
  "id": "PL61",
  "title": "The Work Numbers Come From Porelo's Own Record Of Each Task",
  "state": "Analyzed",
  "size": "L",
  "priority": "High",
  "rank": 1,
  "blockedBy": [],
  "covers": "The project's velocity and an issue's stages are read from the record Porelo keeps of each task, through the store's interface and whatever the backend, and an agent's own stage line counts only until that record exists",
  "openQuestions": 1
}
```

**Size:** L - it crosses repositories: the task's issue and phase go through the window's contract into Porelo's
start, and the planner reads Porelo's records through an interface it does not call today; the velocity moves from the
Git store into the store's interface and `core`.

**What must be true.** The time and tokens an issue's work cost are those Porelo itself measured for the tasks that
worked it, never what an agent wrote about itself, and the velocity an estimate is given comes from them, whatever
backend holds the project's issues.

## Plan

What stands today, read in the code:

- The velocity is `GitStore.velocity(repository, size)`, a method of the Git store alone, not of the `Store`
  interface in `store/api`: `StageRecords.statistic` over `stats/seed.csv` and the repository's `stats/*.jsonl`, the
  seed's minutes plus, per finished issue, the sum of the agent's measured stages of that size; a person's moves are
  left out. A store over Jira or GitHub Issues would have no velocity at all.
- The planner never starts a task and never talks to Porelo's daemon: it answers `PlanningStartTask` (offer,
  repository, issues, role MAIN, BUILDER, READER or DISCUSSION, agent, model, mode, prompt) and the window starts
  it with Porelo's `Start`, then reports STARTED, ENDED or STOPPED with the container's label.
- A task does not always work one issue. A MAIN task works every issue of a story in its repository
  (`WorkProposal`); a BUILDER and a READER work one. The record must carry the issues a task worked.
- Porelo answers a single task's token totals today (`Usage`, by task); a record per task listed for a project since
  a time is not there yet.

The contract the planner builds against, in words: Porelo keeps one record per task - task, project, repository,
the issues it works and its phase, agent, model, effort, start, end, how it ended, token totals - kept after the task
is removed, and answers a project's records since a time through its daemon's socket or `porelo task records
--project <p> --since <t> --json`.

The planner assumes no backend for the numbers. It reads them from Porelo's records and asks the store only for
what every backend has: each issue's size and whether it is finished. Only the Git store may keep a copy of the
numbers, in the repository's `stats/`, as data beside its issues; another store keeps none and loses nothing.

The steps:

1. **The phase at the start.** `PlanningStartTask` gains `phase`, the working state of the project's workflow the
   task works in (Implementing, Reading, Reworking), as the work rules name it; the window hands issues and phase to
   Porelo's `Start`. A change to the window's contract in `openapi/planner.yaml`, `core` (`WorkProposal`,
   `NextStep`) and the screen package.
2. **The records read.** A port in `core`, `TaskRecords.since(project, instant)`, with an adapter in `app` that calls
   Porelo's command line (the socket only if the command line cannot serve), validating every field before use and
   failing closed with the reason when Porelo cannot answer.
3. **The velocity in `core`.** `Velocity.of(repository, size)` in `core` over the task records and the store's
   issues (size, finished), with no Git: a finished issue's sample is the sum of its records' minutes. The `Store`
   interface gains only what the computation needs and every backend can answer; `GitStore.velocity` and its
   caller go, and the app asks `core`.
4. **The Git store's copy.** The `Store` interface gains an optional keeping of an issue's numbers when it is
   finished, which the Git store implements by writing the records' lines into `stats/<ID>.jsonl` and another store
   leaves empty. Where Porelo's records are gone (another computer, an issue finished before them), `core` reads
   the samples the store kept, and the agent's own stage lines and `seed.csv` count only for issues no record
   covers, so nothing is counted twice.

Tests, red first:

- Unit, `core`, over a fake store and fake records: a task record of two hours on a finished issue of size M gives
  a velocity sample of 120 minutes for M; a stage line the store kept for the same issue and phase is not counted
  twice; a store that keeps nothing still gives the velocity from the records alone.
- Unit, the adapter over a fake command: a record with a negative duration, an unknown phase or a project other
  than the one asked is refused with the field named; Porelo unreachable fails the velocity with the reason, never
  an answer from less.
- Unit, `store/git`: an issue's records kept on finishing are read back from a fresh clone as its samples.
- Unit, `WorkProposalTest`: each planned task carries the phase it starts in.

## Acceptance

- The tests above, each seen red first; the full build green.
- On a local VM with Porelo's records available, one issue built by a task through Reading to Review gives a velocity
  sample equal to the task's wall time as Porelo shows it.

## Open questions

- How is a task that works several issues counted - its minutes and tokens split evenly among them, or counted only
  where it worked one issue, or does the planner start one task per issue? A MAIN task works every issue of a story
  in its repository, so its time cannot be told apart per issue from Porelo's record alone; splitting evenly is
  simple but wrong for issues of different sizes, counting only one-issue tasks loses the MAIN tasks' samples, and
  one task per issue changes how work is started.

## To be checked

- The names and form of Porelo's records answer, agreed in the channel, and whether the daemon's socket reaches the
  planner on Windows.
- How an agent's stage name (`implement`, `review`, ...) maps to the workflow state a task record names, so the same
  work is recognized.
- Whether the window already hands Porelo's `Start` an issue, and what it must add for the phase.
- Which store call the velocity needs beyond the board the store already answers (size and state of every issue,
  settled ones too), read in `store/api`.
