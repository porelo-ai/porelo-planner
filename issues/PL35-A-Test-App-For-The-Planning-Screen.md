# PL35 — A Test App For The Planning Screen

```json
{
  "$schema": "issue-1.json",
  "id": "PL35",
  "title": "A Test App For The Planning Screen",
  "state": "Analyzed",
  "size": "M",
  "priority": "High",
  "rank": 22,
  "blockedBy": [],
  "covers": "A small Flutter app that embeds only the planning screen against a mock: what the window hands the screen is entered by hand, every call the screen makes toward the window is shown, and the window's events back - a task started, ended or stopped, its answer - are sent by hand to see the board react",
  "openQuestions": 1
}
```

**Size:** M - one new Flutter module beside `screen/` with its widget tests and its place in the build; no contract
changes, since the app uses only what the screen package exports.

**What must be true.** The planning screen can be tried alone, without the window, against a mock. Everything the
window normally hands the screen can be entered by hand; every call the screen makes toward the window can be seen
with its arguments; and every event the window would send back can be fired by hand to watch the board react.

- **From the window, entered by hand:** the providers with their projects and connection, the current provider, the
  project chosen elsewhere, the place (story, issue), the agents offered, and how the window answers a start (a task
  and container named, a failure with words, no answer) and the opening of a task (possible, or unavailable with a
  reason).
- **Toward the window, shown:** every call of the screen's hooks - `host.go`, `host.openLink`, `host.openNeedsYou`,
  the Start hook, a task's start reported, a task's stop, a task opened - each with its time and arguments, in a log
  that can be copied and cleared.
- **From the window, fired by hand:** a task's event - started, ended (finished or not, why, the answer, unpushed
  commits) or stopped - with its machine and container; and for the board itself a new revision, a dropped stream
  and a refused save.

## Why

The operator's test of the board in the window mixes the screen's faults with the window's and the planner's, and
needs a real project and a real agent run to see how the board takes a task's end. A test app shows the screen's
own contract alone and makes every reaction reachable at once.

A proposed design is drawn on a canvas; the operator reads it before the analysis.

## Decided

- The data behind the screen can be switched in the app: the in-package fake, which needs no process, or the
  planner's mock mode over HTTP, which shows the real contract.
- The app is a module of its own beside `screen/`, embedding the screen package by path.

## Plan

Read in today's code: the screen package exports the view (`PlanningView`), the hooks (`hooks.dart`:
`PlanningHost`, `PlanningStartTask`, `PlanningTaskChanges`, `PlanningAgents`), the client with its report routing
(`PlanningClient`, `PlanningTaskReports`), the HTTP source (`PlannerSource`) and, in `planning_fake.dart`, the fake
`MockPlanningSource`, which can already refuse, hold a save, fail a watch and change a board. The window's own wiring
(`planning_embedding.dart`, `planning_reports.dart` in the window) is the pattern; nothing of it is copied.

- A module `screen-app/` beside `screen/`: a Flutter Linux desktop app with `porelo_planning` as a path dependency,
  a `pom.xml` driving `flutter test` and `flutter build linux` as `screen/pom.xml` drives Flutter, listed only under
  the root's profile `all` and run in the Build workflow's Flutter job; a `README.md` as every module has.
- Recording hooks: a host, task changes, a Start hook and an agent list, each writing every call with its time and
  arguments into one log the app shows, copies and clears; the Start hook answers as the form says - a task and a
  container, a refusal with words, or a future that never completes.
- The forms: providers with their projects and connection, the current provider and project, the place, the agents;
  the source switched between the fake and a `PlannerSource` on a planner started with `--mock`, whose address is
  read from its `planner.json` or entered.
- Events fired by hand go through the same `taskEvent` the window calls, with the offer, container and machine of a
  task the log shows started; the board's own events act on the fake (a new revision, a dropped watch, a refused
  save) and are offered only there, said so beside them.
- Red first, widget tests of the app over the fake: a Start answered with a refusal logs the Start hook's call with
  its arguments and the board shows the refusal's words; an ended event fired for a started task that did not finish
  raises the issue, and its Needs-you button logs `openNeedsYou`.

## Acceptance

- Run from the source with the fake and with a `--mock` planner: each of the hooks above appears in the log with its
  arguments when the screen calls it, each event fired by hand changes the board as the window's event would, and the
  app's widget tests run in the Build workflow's Flutter job.

## Open questions

- Is the app only run from the source by whoever tests the screen, or built and shipped as a package too?

## To be checked

- The drawn design, once read, against this plan: which forms it has and where the log stands.
- Whether the planner's mock mode can show a refused save and a new revision over HTTP today, or only the fake can.
