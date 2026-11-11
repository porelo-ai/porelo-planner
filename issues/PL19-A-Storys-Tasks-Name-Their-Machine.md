# PL19 — A Story's Tasks Name Their Machine

```json
{
  "$schema": "issue-1.json",
  "id": "PL19",
  "title": "A Story's Tasks Name Their Machine",
  "state": "Backlog",
  "size": "M",
  "priority": "Medium",
  "rank": 10,
  "blockedBy": ["PL09"],
  "covers": "The window's report of a task it started names the machine it runs on, and a story's work answers it per task, so a task opened from the board opens on its machine"
}
```

**Size:** M - one field through a contract the window reads: the task report, the run record and the story's work.

**What must be true.** A task opened from the planning board opens through the machine it runs on: the window names
that machine when it reports a task it started, the planner keeps it with the task, and a story's work answers it
beside each task. The planner reaches no machine itself; it only tells back what the window reported.
The name is the one the person sees, 1 to 128 characters without control characters or line breaks, kept and
answered back verbatim, never parsed and never put into a path, a file name or a URL.

## Plan

- **Contract:** `TaskEventRequest` gets a required `machine`: 1 to 128 characters, none a control character (line
  breaks included). Each task of `PlanWork` answers `machine`, null until a report names it. The description says the
  planner keeps it verbatim and never uses it as a path, a file name or a URL.
- **Request:** `WorkRequests.report` reads `machine` with that check, refusing anything else as `BadRequest` before the
  run is touched. `WorkRun.Report` carries it.
- **Run:** `WorkRun.Offer` keeps `machine` beside `containerId`, bound on STARTED. A later report naming another machine
  for the same task is refused as a contradiction of the task binding, like another container. The report's
  fingerprint includes it, so a replay with another machine is no replay.
- **Record:** `WorkRecord` writes and reads `machine` with the offer. A record written without it reads as null, and the
  record's version stays the same.
- **Answer:** `WorkAnswers` answers `machine` per task.
- **Tests, red first:**
  - unit: `WorkRunTest`, the machine bound on STARTED and another machine refused;
  - unit: `WorkRecordTest`, a name such as `WSL Ubuntu` with a non-ASCII letter kept verbatim through a restart;
  - loopback HTTP: `WorkRunHttpTest`, a missing, empty, 129-character or line-breaking name refused before any change,
    and the name answered in the story's work;
  - `ApiMatchesDescriptionTest` holds the field in both schemas.

## Acceptance

- A report without a well-formed machine changes nothing. A task's machine is answered exactly as the window sent it,
  also after the planner restarts. A report naming another machine for a bound task is refused. The guards are seen
  red, then green.
