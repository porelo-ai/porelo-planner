# PL46 — An Issue Says Whether An Agent Works It

```json
{
  "$schema": "issue-1.json",
  "id": "PL46",
  "title": "An Issue Says Whether An Agent Works It",
  "state": "Review",
  "size": "M",
  "priority": "Medium",
  "rank": 29,
  "blockedBy": [],
  "covers": "Every issue's head carries who works it, Agent or Manual, read as Agent where it names none; the planner saves it through its own call, and the board shows it"
}
```

**Size:** M - one field through the store, one call in the contract with the window, and the screen's issue row and
New issue dialog; the work obeying it is an issue of its own, [PL65](README.md#pl65), and ships with this one.

**What must be true.** Every issue says whether an agent or a person works it, the planner keeps that in the issue's
head and saves it as it saves a move, and a person sees it on the board.

## Plan

- The head keeps `worker`, `Agent` or `Manual`, written last, after every other optional field, as the build tools'
  issue schema and head take it; a head without it reads as Agent, and a settled issue's index row as Agent too.
- `store/api`: `Issue` gets `worker`, `AGENT` or `MANUAL`; `Writes` gets the workers a save sets, by issue id;
  `NewIssue` gets the worker a new issue starts with.
- `store/git`: `StoreReader` reads `worker` from the head, an unknown value or case is a fault that leaves the issue
  out as any broken head does; a sub-issue reads its parent's worker. The schema the store holds (`issue-1.json`,
  `IssueSchema`) takes the field. `HeadEdit` sets or inserts it; `GitStore.save` writes a changed worker into the
  head, leaves the index alone, skips an unchanged one, and commits and pushes it as a move, in the same commit as
  moves of the same save, with a subject that says so. A worker set on a sub-issue, or on an issue the same save
  closes, is refused. `IdeaFile` writes a new issue's worker.
- `core`: a worker change of an issue a run's task owns, itself or through one of its sub-issues, is refused, as a
  move is, under the work record's lock.
  `NewIssues` takes the new issue's worker, `AGENT` when none is given.
- The contract, written first: `Issue.worker`; `NewIssueRequest.worker`, optional, `AGENT` by default; and
  `PUT /projects/{project}/issues/{issue}/worker` with `{worker, revision}`, answered as a move is, with the
  refusals a move has (`InvalidName`, `NoSuchProject`, `NoSuchIssue`, `StaleRevision`, `WorkRunning`, the store's)
  and one for a sub-issue. The mock board names a worker for every issue.
- The screen package: the issue's row shows Agent or Manual; the New issue dialog starts at Agent. The switch and
  the choice of Manual are offered only once [PL65](README.md#pl65) is in; until then the package sends no worker
  change.
- Every issue whose head has no `worker` counts as Agent, so work starts as it does today; the changelog says so.
- Red first: in `store/git`, a worker set and saved over local bare repositories is read back from a fresh clone, a
  head without it reads Agent, an unknown value is a fault, a sub-issue's worker is its parent's; in `core`, a worker
  change of an issue a running task owns is refused; in `app`, the call's refusals; in `screen`, the row shows the
  worker.
- Shipping: writing `worker` into a head waits until the build tools publish the schema that takes it; the planner's
  build then takes that snapshot.

## Reading

The final code was read by another model. Its four findings are fixed, the first three each with a test seen red
first: a worker change while a running task owns a sub-issue of the issue is refused; a save that changes only a
worker leaves the stories index and the own repository alone; the board a save checks keeps each issue's worker; and
the contract names `worker` among a new issue's refused fields and among what a store may not save (wording only).

## Acceptance

- A worker set through the planner is read back from a fresh clone, a head without one reads Agent, a sub-issue
  reads its parent's, and a change while a task owns the issue is refused.
