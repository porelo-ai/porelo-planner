# PL105 — ST311 Played Through On The Test VM

```json
{
  "$schema": "issue-1.json",
  "id": "PL105",
  "title": "ST311 Played Through On The Test VM",
  "state": "Backlog",
  "size": "M",
  "priority": "Medium",
  "rank": 41,
  "blockedBy": ["PL104"],
  "covers": "A script plays the test of story ST311 once on the local test VM through the planning screen against the real planner, as a person opens Planning, and lays the screen driver the other screen story tests use"
}
```

**Size:** M - two modules: the screen driver in `screen/` that the other screen story tests take over, and the
story's script in `tool/story-tests/`, with one run on the VM.

**Estimate:** 67 min, the measured median of M.

**What must be true.** The test of story ST311, "A planner app serves the board to the window", runs once, automated,
on the local test VM the way a person plays it in the window, as story ST347 asks of every High story: the planner
started beside the window, Planning opened, stories in their columns by priority, a story and an issue opened, a move
saved into the project's Git, a story kept in a state its issues do not fit after a warning naming them, and every
call the screen made named in the OpenAPI description.

## Plan

- **Where.** `tool/story-tests/st311.sh`, on the shared ground of [PL104](README.md#pl104) (forge, store fixture,
  planner started as the window starts it, step report, clean-up), and the screen driver in `screen/story_test/`:
  `planner_driver.dart`, shared, and `st311_test.dart`. Outside `screen/test/`, so the screen's own suite never runs
  them.
- **How it plays the window's pages.** The window embeds `PlanningScreen` and hands it an `HttpPlanningSource` it
  opens from the planner's address file; the driver does the same with `HttpPlanningSource.fromAddressFile` and
  mounts `PlanningScreen` in a `MaterialApp`, its start and agent hooks stubbed, since this story starts no task.
  It runs as `flutter test story_test/st311_test.dart` in the screen package of the bundle on the VM, the real HTTP
  inside `tester.runAsync`, as the screen's `mock_served_test.dart` already reaches a server. It finds and taps what a
  person sees - a column's heading, a card's title, the issue row, the move menu, the warning's confirm - and prints
  each step in the shared report's form. An `HttpOverrides` wrapper records each call's method and path.
- **The steps**, as the operator plays them:
  1. The planner from the bundle starts as the window starts it and answers `GET /health` UP.
  2. Planning opens: every fixture story stands in its state's column, ordered by priority.
  3. A story opens and shows its description and issues; one issue opens and shows what must be true.
  4. A story moved on the board and saved is, in a fresh clone of the project's repository, in that state, with
     the stories index rewritten.
  5. A story moved to a state its issues do not fit shows the warning naming those issues; confirmed, it keeps that
     state on the board and in Git.
  6. Every call the screen made is a path and method of `planner.yaml`, read from the bundle line by line.
- **This repository's issues of the story not in Review:** [PL125](README.md#pl125), reading private repositories with
  Porelo's login. The fixture's forge takes the account's own SSH key, so no step plays it; the run names it with
  `--expect` as an expected failure of the story, not of a step.

**Rules for this story test (operator)**

- A test script is test tooling, not production code: no tests for the test scripts, no red-first, no review loops (one Sonnet reading at most). The script is proven by its run on the VM.
- On the VM a failing script is fixed ad hoc on the spot and the run carries on; every fault found (in the script or in the product) is collected, not fixed one by one. After the run: product faults become estimated Backlog issues, script fixes go into one commit, then one new run.
- One VM run at a time on this host, through the coordinator: ask before a run starts and post when it ends. No second VM, no Molecule beside it; clean up after.
- A story whose issues are not all in Review yet still gets its script and one run; the run names the missing issue as an expected failure.

## Acceptance

- One run of `st311.sh` on the local test VM, its output naming each step passed or failed and the missing issue as
  an expected failure, and the VM cleaned up: no planner running, no key authorized, no directory of the run.

## To be checked

- Whether the run's account on the VM has the Flutter of the screen's pin, or `flutter test` must run on the host
  against the VM's planner through an SSH forward of its loopback port, the address file copied over standard input.
- Whether `PlanningScreen` needs any hook beyond start, task changes, agents and host to open a story and an issue.
- Whether the window starts the planner with `--end-with-input` by the time of the run; the script starts it as the
  window's code does then.
