# PL24 — Following And Stopping Story Work In The Screen

```json
{
  "$schema": "issue-1.json",
  "id": "PL24",
  "title": "Following And Stopping Story Work In The Screen",
  "state": "Review",
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
  required container, required machine, PlanningTaskEnded? ended})` calls `POST /projects/{project}/tasks/{task}/events`
  with `{offer,event,container,machine,ended?}`; `container` is the container's name; `machine` is the machine's name as
  the person sees it, 1 to 128 characters without control characters or line breaks, kept and answered back verbatim
  and never put into a path, a file name or a URL; `PlanningTaskEnded` Porelo's record of the
  agent's end unmapped, `{at,finished,why,answer,unpushed}`. The answer `{revision,work,tasks}` is checked whole, and
  its still-valid offers go to the Start hook once, bound to the provider, project and story they came from, never to
  the board shown now. A stop voids pending starts; `PlanningTaskChanges` lets the window stop a task whose Start
  finished after the stop, without a report, since the planner voided its offer.
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

## Taken from the plan reading

- **Kept on the planner's side, not checked again in the screen:**
  - the bounds of an end record, its timestamp, and whether `ended` fits the event;
  - the container's form and the size of a request;
  - the number of findings, their numbers, the length of their texts and the meaning of their paths.

  The screen keeps the record whole and checks identities before it acts. It shows paths only as text.
- **A received machine name is not checked again.** The screen only shows it. A machine is checked once, when the
  first STARTED report binds it.
- **A reading's choice may carry a comment with a choice other than Rework**, because the served contract allows it.
  The screen sends an empty comment for those choices.
- **The reason in Needs you has no test of its own.** The planner sends a reason other than NONE only together with
  `needsYou`, so `needsYou` alone already keeps such an issue visible. A test with a reason but without `needsYou`
  would state what the planner never sends.

## Taken from the code reading

- **Follow-ups are not gated on `atWork`.** The planner marks work as at work only while a task has started. After the
  last task ends, it answers with the next offer and `atWork` false. A stop is read from the issues' reason `STOPPED`
  and from the screen's own stop.
- **Answers are bounded like the work read.** A report's or a decision's answer carries the whole work, every task's
  end answer and the follow-ups' prompts, so 64 KiB is too little for a valid answer.
- **A stop with no recorded run answers `{"work": null}`**, and that is a valid answer.
- **A task whose Start finished after a stop is stopped and not reported.** The stop voided its offer, so the planner
  refuses any report on it. A failed STARTED report binds no machine.
- **A follow-up is checked for its role, its issue and the run's mode** before it goes to Start. A decision's
  follow-up must also be for the decided issue, with the role its choice makes.
- **Declined: holding a large end answer.** Every answer stays under the response's size bound, and the screen
  shortens it for display.
