# PL35 — A Test App For The Planning Screen

```json
{
  "$schema": "issue-1.json",
  "id": "PL35",
  "title": "A Test App For The Planning Screen",
  "state": "Review",
  "size": "M",
  "priority": "High",
  "rank": 22,
  "blockedBy": [],
  "covers": "A small Flutter app, run only from the source, that embeds only the planning screen against a mock: what the window hands the screen is entered by hand, every call the screen makes toward the window is shown, and the window's events back - a task started, ended or stopped, its answer - are sent by hand to see the board react"
}
```

**Size:** M - one new Flutter module beside `screen/` with its widget tests and its place in the build; no contract
changes, since the app uses only what the screen package exports. Dropping the build of a Linux app leaves the size:
it was one Maven step, and the work lies in the forms, the recording hooks and the events fired by hand.

**Estimate:** 67 min, the measured median of M. Uncertain: the drawn design is not yet read against the plan, and its
forms may hold more than the plan names.

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
- The app is only run from the source by whoever tests the screen; it is never built as a package or shipped.

## Plan

Read in today's code: the screen package exports the view (`PlanningView`), the hooks (`hooks.dart`:
`PlanningHost`, `PlanningStartTask`, `PlanningTaskChanges`, `PlanningAgents`), the client with its report routing
(`PlanningClient`, `PlanningTaskReports`), the HTTP source (`HttpPlanningSource`, from a `PlannerAddress`) and, in the
second library `planning_fake.dart`, the fake `MockPlanningSource` with `MockPlanningClient`, which can already refuse,
hold a save, fail a watch and change a board. The app imports both libraries and changes neither's exports. The window's own wiring
(`planning_embedding.dart`, `planning_reports.dart` in the window) is the pattern; nothing of it is copied.

- A module `screen-app/` beside `screen/`: a Flutter Linux desktop app with `porelo_planning` as a path dependency,
  run with `flutter run -d linux` from the source; a `pom.xml` driving only `flutter test` as `screen/pom.xml` drives
  Flutter, with no build of the app and nothing deployed or packaged, listed only under the root's profile `all` and
  run in the Build workflow's Flutter job; a `README.md` as every module has, saying how to run it.
- Recording hooks: a host, task changes, a Start hook and an agent list, each writing every call with its time and
  arguments into one log the app shows, copies and clears; the Start hook answers as the form says - a task and a
  container, a refusal with words, or a future that never completes.
- The forms: providers with their projects and connection, the current provider and project, the place, the agents;
  the source switched between the fake and an `HttpPlanningSource` on a planner started with `--mock`. Its address is
  read only from the runtime directory the person enters, with `PlannerAddress.read` and its owner and mode checks;
  the account's own runtime directory and those under `/run/user` are refused, as the mock mode refuses them, since
  the person's real planner writes its address and token there. The entered path is compared as written and with
  every link resolved, `/var/run` to `/run` among them, and the address is read from the resolved path; a path that
  cannot be resolved is refused. No token is entered, shown or logged, and the
  project is named to the planner with a fixed example repository the mock does not read.
- Nothing builds, runs or packages the app: its `pom.xml` runs `flutter clean`, `flutter pub get`, `dart format`, `dart analyze` and
  `flutter test` only, and its widget tests need no Linux toolchain. The Build workflow's Flutter job runs
  `-pl screen,screen-app`; the app pins the screen's Flutter.
- The fake learns what the planner does with a run that did not finish: an ENDED report with `finished` false raises
  the task's issues with the reason `FAILED`, so an end fired by hand on the fake raises the card as on the planner. The fake
  covers what the app fires; a declared `FAILED` outcome or unpushed commits raise an issue on the planner alone.
- Events fired by hand go through the same `taskEvent` the window calls, with the offer, container and machine of a
  task the log shows started; the board's own events act on the fake (a new revision, a dropped watch, a refused
  save) and are offered only there, said so beside them.
- Red first, widget tests of the app over the fake: a Start answered with a refusal logs the Start hook's call with
  its arguments and the board shows the refusal's words; an ended event fired for a started task that did not finish
  raises the issue, and its Needs-you button logs `openNeedsYou`; a unit test refuses the account's own runtime
  directory however it is written.

The plan's reading by another model found the HTTP source misnamed, the address and token of the person's real
planner within reach of a directory read by default, and the Flutter job running only the screen; all are taken in
above. The planner's mock mode cannot be made to refuse a save or move its revision from outside: it has no route but
its reset, so the board's own events stay on the fake. The drawn design could not be reached from here; the forms
follow this plan, with the log in a tab of its own beside them.

## Acceptance

- Run from the source with the fake and with a `--mock` planner: each of the hooks above appears in the log with its
  arguments when the screen calls it, each event fired by hand changes the board as the window's event would, and the
  app's widget tests run in the Build workflow's Flutter job; no build publishes or packages the app.
