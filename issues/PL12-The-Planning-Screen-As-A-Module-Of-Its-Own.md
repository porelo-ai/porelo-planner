# PL12 — The Planning Screen As A Module Of Its Own

```json
{
  "$schema": "issue-1.json",
  "id": "PL12",
  "title": "The Planning Screen As A Module Of Its Own",
  "state": "Implementing",
  "size": "M",
  "priority": "Medium",
  "rank": 1,
  "blockedBy": [],
  "covers": "The window's planning screen as a Flutter package in the module `screen/`, built by Maven only in a full build, tested alone and against the mock mode; embedded by the window through a Git ref"
}
```

**Why this size.** a Flutter package in a Maven module of its own, built and tested by Maven only in a full build, with its
own CI job beside the Java build and a test against the planner's mock mode; the screen's code itself is moved in
from the window as it is.

**What must be true.** The window's planning screen - board, story, issue, the work proposal and the reading choice -
is a Flutter package in porelo-planner's module `screen/`, which the window embeds by a fixed Git ref and path and
hands a client of the planner (from its address and token), a way to start a task through Porelo's `Start`, the task
changes it sees, and the agents and models a start may choose. The package is tested on its own, against a fake of its REST/SSE client and
against the planner started in its mock mode, without Porelo and without the window.

## Plan

What stands already: the module `screen/` (package `porelo_planning`, Flutter pinned in `pubspec.yaml`, listed only
under the root's profile `all`), Maven driving `flutter pub get`, `dart format`, `dart analyze` and `flutter test` in
`screen/pom.xml`, the job `screen` in `build.yml`, the public API in `lib/porelo_planning.dart` (`PlanningScreen`,
`HttpPlanningSource`, `PlanningAction`, `hasPendingMoves` with `pending_moves_test.dart`), and the access check's
variants as unit tests (`AccessFilterTest`, `LoopbackBindingTest`). What is missing is the screen tested against the
planner's mock mode: its HTTP source is tested only against answers written by hand in the test.

- **The mock's answers, recorded by the planner's own test.** `MockAnswersTest` in `app` (integration level, the
  planner started with `--mock` as `MockModeTest` starts it) asks what the screen asks at a board's start - `PUT
  /projects/{p}` with a source, `GET /projects/{p}/workflow`, `GET /projects/{p}/stories` and the first `board` event
  of `GET /projects/{p}/events`, then `GET /projects/{p}/issues/{id}` of one issue and the answer of one move - and
  compares each answer with its file under `screen/test/served/mock/`. The files hold JSON bodies only (of the event,
  its `data` line), no header, port, token or time; the mock's revisions are fixed (`mock@1`, `mock@2`). A difference
  fails naming the file and both texts; started with `-Dplanner.mock.record=true` it writes the files instead, and
  refuses to where the environment names `CI`, so a build never rewrites what it checks. So a change of what the mock
  serves cannot leave the screen's copy behind; the refresh, the task stream and the other writes stay out of this
  guarantee and are held by the screen's tests over hand-written answers.
- **The screen reads them.** `mock_served_test.dart` serves those files from a local `HttpServer` behind the token,
  as the planner does, at the paths the source builds; a request without the token, or for a path not recorded,
  is refused, so a dropped header or an unexpected call fails. It reads a board, one issue and one move through
  `HttpPlanningSource`: the snapshot's states, phases, stories and each story's issues with their states and workers
  equal those of the screen's own `MockPlanningSource` board from `planning_data.dart`, the reference the window's
  mock tests use, and a few fields are asserted literally (an issue's worker, what it is blocked by, its repository,
  the move's new revision), since the mock's board is a copy of that reference. A field the planner serves that the
  screen does not read, or reads under another name, shows as a difference.
- No `example/` app and no `integration_test` under a display server: the board's widgets are tested over
  `MockPlanningSource` already, and what only a running planner can show is its answers, which the two tests above
  hold on each side; the window built against a ref of this repository is the operator's acceptance.
- **Red first:** the Dart test against one recorded file with an issue's `state` renamed (fails naming the issue),
  and with the source's token header dropped; the Java test against a recorded file with one story left out, and
  record mode with `CI` set.

## Acceptance

- `./mvnw -B -s settings.xml verify` builds without Flutter; `-Pall verify` runs the package's analysis and tests,
  among them the test over the mock's recorded answers.
- `MockAnswersTest` fails when the mock serves anything other than the recorded files.
- The window built against a ref of this repository shows the board from the planner's mock mode.
