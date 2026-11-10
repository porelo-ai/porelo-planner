# PL09 — The Run From Building To Review

```json
{
  "$schema": "issue-1.json",
  "id": "PL09",
  "title": "The Run From Building To Review",
  "state": "Implementing",
  "size": "L",
  "priority": "Medium",
  "rank": 8,
  "blockedBy": ["PL08"],
  "covers": "The run from building through Reading and Reworking to Review: the reading limit, Needs you and its three choices, findings, a local record; task changes told by the window"
}
```

**Why this size.** the flow build -> Reading -> Reworking or Review, the reading limit, the operator's choice when it is
reached, the findings and a record that survives a restart.

**What must be true.** When a task of a story's work ends, the planner judges its end once: success leads to the
next working state (Reading) or to the done state; a reading with blocking findings leads to Reworking while the issue
has readings left, notes alone go with the issue into Review. On the last allowed reading with blocking findings the
issue stays in Reading, nobody at work, under *Needs you* with its findings, and the operator chooses Review anyway,
one more reading, or Rework with their own words. A task blocked or failed needs the operator and never counts as a
finding. Counts, limits and findings survive a restart of the planner.

## Plan

- **The window tells the planner of every change of a Porelo task it started** - started, ended, stopped - since the
  window already watches Porelo and the planner talks to no Porelo. The end is the task's `ended` record (outcome `DONE` or `BLOCKED`, why, the agent's last
  message; FAILED when the run did not finish). A reader's last message carries one `Findings:` block, one finding a
  line, `<n>. <blocking|note> | <file or -> | <line or -> | <text>`.
- `core`: `WorkEnds` (the pure judge and the findings parser), `ReadingCycle` (rounds, limit, ROUND_LIMIT, the
  three choices), `NextStep` (which state, which tasks to start with which prompt - the window starts them).
- **The record is local to the computer**, as the tasks it follows run there; only the states go into the Git
  store, so a lost record leaves every issue in its right state without its counts and findings:
  `$XDG_STATE_HOME/porelo-planner/<project>/work.json`, replaced atomically, one writer per project:
  tasks with their container ids, rounds, limits, findings and every reading's raw answer.
- REST: `POST /projects/{project}/tasks/{task}/events` with `{event: STARTED|ENDED|STOPPED, container, ended}` (the
  window reports each change; a report repeated is taken once), `GET .../stories/{story}/work`,
  `POST .../issues/{issue}/reading` with `{round, choice, comment, revision}`; the issue views carry `rounds`,
  `roundsMost`, `atWork`, `needsYou`, `readingStopped`, `findings`.
- **Red first:** `WorkEndsTest` (a malformed block needs the operator; a pipe in a finding's text kept);
  `ReadingCycleTest` (the limit reached with blocking findings stops in Reading; success on the last reading reaches
  Review; one more round grants `max(roundsMost, rounds + 1)`); `WorkRecordTest` (a restart keeps counts and
  findings; the same end reported twice counts one reading).

## Acceptance

- The red-first tests seen red, then green; a whole cycle run over fakes in seconds.
