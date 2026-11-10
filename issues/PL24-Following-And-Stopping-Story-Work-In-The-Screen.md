# PL24 — Following And Stopping Story Work In The Screen

```json
{
  "$schema": "issue-1.json",
  "id": "PL24",
  "title": "Following And Stopping Story Work In The Screen",
  "state": "Backlog",
  "size": "M",
  "priority": "Medium",
  "rank": 10,
  "blockedBy": ["PL09"],
  "covers": "The screen package's calls for a story's run: following and stopping work, the window's task reports, an issue's findings and a reading round's decision, each follow-up task handed to the window's Start once"
}
```

**Size:** M - the run's four calls in one package against a served contract, with the follow-up tasks taking the
Start hook already built.

**What must be true.** A window that embeds the planning screen can follow a story's work and stop it, report every
change of a task it started, and decide a reading stopped at its limit: each of these calls of the package's
`HttpPlanningSource` calls the planner, answers its refusals as `PlanningRefusal`, and hands every follow-up task the
planner answers to the window's Start hook once.

## Plan

- **Reads and decisions:** `GET /projects/{project}/stories/{story}/work` has no body and returns `{work}`, `null`
  for no run; `POST /projects/{project}/stories/{story}/work/stop` has an empty body and returns `{work}`.
  `getPlanningIssue` uses `GET /projects/{project}/issues/{issue}` and reads findings inside `{revision,issue}`.
  `POST /projects/{project}/issues/{issue}/reading` sends `{story,round,choice,comment,revision}` and returns
  `{issue,work,revision,tasks}`. Absent or malformed findings stay unreported. `ReadingResolution`, `ReadingDecision`
  and the window's handoff take the returned tasks through the Start hook; no start follows a refusal.
- **Task reports, as agreed with the window:** `PlanningClient.taskEvent(project, task, event, {required offer,
  required container, PlanningTaskEnded? ended})` calls `POST /projects/{project}/tasks/{task}/events` with
  `{offer,event,container,ended?}`; `container` is the container's name, `PlanningTaskEnded` Porelo's record of the
  agent's end unmapped, `{at,finished,why,answer,unpushed}`. The answer `{revision,work,tasks}` is checked whole, and
  its still-valid offers go to the Start hook once, bound to the provider, project and story they came from, never to
  the board shown now. A stop voids pending starts; `PlanningTaskChanges` lets the window stop the active tasks and
  report STOPPED.
- **Shown state:** activity stays until a report confirms it ended; Needs you lists every issue whose run reason is not
  NONE with its raw answer, DONE readings with invalid findings and refused saves included; the three reading choices
  appear only for a stopped reading, and a choice reads its findings for the round and revision it is made on. The
  HTTP source's watch passes a changed run at an unchanged store revision.
- **Red first:** loopback HTTP cases for each call's exact request, envelope and refusals (a report or decision taken
  as success when refused, a follow-up started twice or for another story), and unit cases for the shown state (a
  hidden Needs you, decisions offered on a reading not stopped).

## Acceptance

- Reads tell no run from a malformed answer; a stop keeps states, rounds and findings until reports confirm the tasks
  ended. All three reading choices use the exact story, issue, round and revision, require Rework words, and hand only
  returned follow-up tasks to Start, once. The guards seen red, then green; the screen's checks and the document checks
  pass.

## To be checked

- Compare the served paths, envelopes, refusals, offers, stop and report order and the same-revision events with the
  plan above once the planner serves the run.
- The window's adapter passes the offer, Porelo's task name, the container's name and the unmapped end record, keeps
  the originating source and story, and starts a returned offer once.
- Work without issues and a lost record: missing rounds and findings stay unknown, granted readings may exceed 10, and
  activity or permission to go on is never read from planning states alone.
