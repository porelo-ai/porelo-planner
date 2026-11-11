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

- **The module:** `screen/` with `pom.xml` (packaging `pom`, artifactId `porelo-planner-screen`), `README.md`,
  `pubspec.yaml` (package `porelo_planning`, Flutter pinned to the version the window pins, read from there when it
  moves), `lib/`, `test/`, `example/`. In the root's `<modules>` only under the profile `all`, so a Java build needs
  no Flutter.
- **Maven drives Flutter** under `all` with `exec-maven-plugin`, as the window's build does: `flutter pub get`,
  `dart format --set-exit-if-changed`, `dart analyze` (never `flutter analyze`), `flutter test`; the tests tagged
  `documents` stay Java's.
- **The window's hooks**, as the package's public API (`lib/porelo_planning.dart`):
  - `PlanningScreen(client, startTask, taskChanges, agents)`, where `client` is a `PlanningClient`, an interface of
    the package. `PlanningClient.http(baseUrl, token)` is the planner's REST/SSE client; until it exists the window
    passes one that answers from today's daemon, and the moved tests use a Dart fake of the interface, so the screen
    moves before the planner's API exists, with no change in behavior.
  - The window reports a change of a task it started through the same client,
    `client.taskEvent(project, task, STARTED|ENDED|STOPPED)`, and speaks no HTTP of the planner's itself;
    `taskChanges` is only what the screen shows of a run (outcome and why).
  - `PlanningAction` for the window's command menu, a `hasPendingMoves` getter for its leave guard, and a
    `Listenable` of *Needs you* entries with the widget that shows one.
  - `PlanningStyle` and a small `Space` move into the package; colors come from `Theme.of(context)` and the package
    defines no theme of its own, so the screen looks like the window around it.
- **The move:** the screen's files and their tests come in from porelo-frontend as they are, against a Dart fake of
  today's models; the REST/SSE client follows with the planner's REST calls and its changes stream. The window's
  agent moves them and hands them over once the window starts with the code from this checkout.
- **CI:** a job `screen` in `build.yml` with the pinned Flutter beside the pinned JDK, running `-Pall verify` for the
  module, and an integration test (`example/` driven by `flutter test integration_test` under `xvfb-run`) against the
  planner's mock mode built in the same job.
- `AGENTS.md`'s own part names Flutter and the reason: the window is Flutter, and the screen is part of it.
- **Alongside, in `app`'s tests:** the access check's variants move down to unit tests - the token cases (no
  header, a wrong token, one of the right length, `Basic`, a longer one) into a test of `AccessFilter` over a mock
  request and response, the address cases (empty, `0.0.0.0`, `::`, a documentation address, a name) into a test of
  `LoopbackBinding`'s parse. One refusal through the running server and one refused start stay as integration tests.
  Each moved test is seen red by a break. The branch is rebased on `main` once the access check is there.
- **Red first:** the package's own test of `hasPendingMoves` against a fake with one unsaved move; the integration
  test against a planner that is not started fails naming the address.

## Acceptance

- `./mvnw -B -s settings.xml verify` builds without Flutter; `-Pall verify` runs the package's analysis and tests.
- The window built against a ref of this repository shows the board from the planner's mock mode.

## To be checked

- The window's Flutter pin and its toolchain flags (`libsecret`, Linux desktop), read in porelo-frontend when the
  package moves.
- Whether the integration test needs a display server on the runner, or the widget tests over the mock's HTTP answers
  suffice.
