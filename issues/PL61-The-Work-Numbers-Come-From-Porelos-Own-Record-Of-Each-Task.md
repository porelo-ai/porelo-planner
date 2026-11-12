# PL61 — The Work Numbers Come From Porelo's Own Record Of Each Task

```json
{
  "$schema": "issue-1.json",
  "id": "PL61",
  "title": "The Work Numbers Come From Porelo's Own Record Of Each Task",
  "state": "Review",
  "size": "L",
  "priority": "High",
  "rank": 1,
  "blockedBy": [],
  "covers": "The minutes a repository's issues of one size took are computed in core from the record Porelo keeps of each task over the board's issues, whatever the backend, and the Git store's own velocity goes"
}
```

**Size:** L - it builds against another repository's answer, Porelo's task records, read through a command the
planner runs, and moves the velocity out of the Git store into `core`.

**Estimate:** 113 min, the measured median of L.

**What must be true.** The minutes a repository's issues of one size took are those Porelo itself measured for the
tasks that worked them, never what an agent wrote about itself, whatever backend holds the project's issues.

## Plan

What stands today, read in the code:

- The velocity is `GitStore.velocity(repository, size)`, a method of the Git store alone, called by nothing but its
  test; the number agents' estimates come from is the stage command's `median` and `p80`, which read the agents'
  own stage lines and answer as the build tools do while those carry `stage`. That answer stays as it is.
- `Board` gives every issue of the project, a settled one from its index row, with its repository, size and state,
  and `Workflow.closed` the states that end the work; `Rejected` ends it without a result.

The contract the planner builds against, in words: `porelo task records -p <project> --since <instant> --json`
prints one JSON array, the oldest start first, of one record per task of the project whose end is at or after the
instant, a running task's always. A record carries `task`, `project`, `repository` (or null), `work` (one
`{issue, phase}`, or null), `agent`, `model`, `modelFrom`, `effort`, `startedAt`, `endedAt` (null while it runs),
`end` (`done`, `failed`, `stopped`, `removed`, null while it runs), `totals` (requests and, per kind of token, the
tokens and how many answers named none), `toolCallsAsked` and `toolCalls`; each call carries `task`, `tool`, `kind`,
`start`, `startFrom`, `end`, `endFrom`, `command` (shell calls only), `background` and `issue`, the issue whose
sub-agent made it, or null. Times are UTC instants. Exit 74 means the records cannot be read, 64 a time it refuses;
no records is `[]` with exit 0. It runs on Linux; how the window reaches it elsewhere is not this issue's.

The steps:

1. **The records in `core`.** `TaskRecord` holds what the velocity reads - task, project, repository, the work's
   issue, start, end, how it ended, and each call's issue, start and end - and refuses an end before its start or
   an end without how it ended. The port `TaskRecords.since(project, instant)` answers them or throws
   `TaskRecords.Unanswered` with the reason.
2. **The velocity in `core`.** `Velocity.median(records, board, repository, size)` over the records and the board,
   with no Git. Two records of one task are refused, so no task counts twice. A task counts once it ended, however
   it ended, since a failed, stopped or removed attempt cost time too. Its time goes to its issues, the work's and
   the calls' named once each: an issue named by calls gets the union of its calls' intervals, each cut to the
   task's own start and end, a call without an end giving none; where those spans add up to more than the task's
   time, as parallel sub-agents do, they are scaled down to it, so a task never gives more than it lasted; the rest
   is split evenly among all its issues. The task's `repository` is not read: the board says which repository an
   issue is in. An issue gives a sample, the sum over its tasks, only when the board has it in this repository, of
   this size, and in a closed state other than `Rejected`; a share given to any other issue is no sample and goes to
   no one else. One sample is a velocity; none is no velocity, not zero.
3. **The adapter in `app`.** `PoreloTaskRecords` runs Porelo's executable, given by an absolute path, with each
   argument its own element and no shell: the project checked as a repository name, which never starts with a dash,
   and the instant written by the planner in UTC. The child gets only `PATH`, `HOME`, `LANG` and the `XDG_` state
   and runtime directories, so no secret of the planner's environment reaches it. It reads at most 16 MiB of answer
   and 64 KiB of errors, both drained while it runs, and kills the process and its children after 60 seconds. Every
   field of every record is validated against the contract above before use: a missing or mistyped field, an
   unknown `end`, `kind` or source, an instant not in UTC with a `Z`, an issue id of the wrong shape, a duplicate
   key, or a record of another project fails the whole answer with the record and field named, since an answer
   that holds one wrong record cannot be trusted for the others. Exit 74, exit 64, any other failure, an answer cut
   off and Porelo not found each fail with the reason, never with fewer records.
4. **The Git store's own velocity goes.** `GitStore.velocity` and its test are deleted; `StageRecords.statistic`
   stays for the stage command.

Tests, red first, all unit tests over fakes:

- `core`, `VelocityTest`: a two-hour task on a finished M issue gives 120 minutes for M; a task whose calls name two
  issues for 50 and 30 minutes, with 20 minutes outside them, gives 60 and 40; a task with no work and no call issue
  gives nothing; a running task, a rejected issue, another repository's issue and an issue of another size give no
  sample; two tasks on one issue add up; an issue's calls with a gap between them count only the calls; two
  sub-agents in parallel over a whole task share its time; a call outside the task is cut to it; a task given twice
  is refused.
- `core`, `TaskRecordTest`: an end before the start, or an end time without how it ended, is refused.
- `app`, `PoreloTaskRecordsTest`, over a fake runner: a whole record from Porelo's own form is read; each field
  missing or mistyped is refused with its name; another project, an instant with an offset, exit 74, exit 64, an
  unknown exit and a cut-off answer each fail with the reason. One case runs a real process, a script standing in for Porelo, so the arguments,
  the time limit, the environment it gets and the exit code are those of a process.

What is left for [PL83](README.md#pl83): the phase a task starts in handed through the window's contract, the Git
store keeping an issue's numbers in `stats/`, the stage command's median moved onto the records, the velocity
served to the window, and tokens per issue. Porelo's records do not yet give what an exact split needs - a
sub-agent's own span names no issue and its calls name no parent, tokens are counted per task only, not per
sub-agent or call, and an id with a hyphen never names an issue - so the even split stands in for them.

The reading of the code left two findings as they are: an answer of 16 MiB held as a tree is several times that in
memory, bounded and read once per call, and Jackson's own limits on nesting and string length stand; an assertion
inside a lambda that checks a value already described by the outer `.as(...)` carries no second one.

## Acceptance

- The tests above, each seen red first; the full build and the hand-over checks green.

## To be checked

- That a task's `end` is null exactly while its `endedAt` is, in records Porelo writes, read on a real record once
  one exists.
