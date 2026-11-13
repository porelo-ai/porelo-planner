# PL109 — ST339 Played Through On The Test VM

```json
{
  "$schema": "issue-1.json",
  "id": "PL109",
  "title": "ST339 Played Through On The Test VM",
  "state": "Backlog",
  "size": "S",
  "priority": "Medium",
  "rank": 45,
  "blockedBy": ["PL104"],
  "covers": "A script plays the test of story ST339 once on the local test VM as a person would, running the screen's test app from the source under a virtual display and driving it against its mock, with no window, planner or forge"
}
```

**Size:** S - one script and one integration test of the test app, on the story tests' shared copy and step report,
with one run on the VM; no planner and no forge.

**Estimate:** 33 min, the measured median of S.

**What must be true.** The test of story ST339, "The planning screen tried on its own against a mock", runs once,
automated, on the local test VM the way a person plays it, as story ST347 asks of every High story: the test app run
from the source shows a mock board, takes moves and shows every state the window can hand the screen.

## Plan

- **Where.** `tool/story-tests/st339.sh`, which takes from [PL104](README.md#pl104) only the copy of the bundle to the
  VM, the step report and the clean-up; it starts no planner and makes no forge. The test app's own integration test
  drives it, in the app's `integration_test/` beside its source in `screen/`, wherever the test app is built.
- **How it plays.** As a person runs the app from the source: `flutter run`'s build for Linux on the VM, driven by
  `flutter test integration_test/... -d linux`, headless under `xvfb-run` inside `dbus-run-session`, never on a
  display of the host. The test taps and reads what a person sees and prints each step in the shared report's form.
- **The steps**, as the operator plays them:
  1. The test app's source is in the bundle and builds for Linux from it.
  2. The app starts and shows the planning screen with the mock board: the workflow's columns and the mock's stories.
  3. A move made on the board shows as a pending move and, saved, stands on the board.
  4. Each state the window can hand the screen, entered by hand in the app - not reached yet with its words, a
     refusal, a board that is not live, a story or issue opened - shows on the screen as the window would show it.
  5. A task event sent by hand - started, ended, stopped - is reacted to on the board, and every call the screen
     makes toward the window is listed in the app.
- **This repository's issues of the story not in Review:** [PL35](README.md#pl35), the test app itself, Backlog. Until
  it is built, step 1 fails as expected and the steps after it print `SKIPPED` naming step 1; the run is started with
  `--expect 1=PL35`.

**Rules for this story test (operator)**

- A test script is test tooling, not production code: no tests for the test scripts, no red-first, no review loops (one Sonnet reading at most). The script is proven by its run on the VM.
- On the VM a failing script is fixed ad hoc on the spot and the run carries on; every fault found (in the script or in the product) is collected, not fixed one by one. After the run: product faults become estimated Backlog issues, script fixes go into one commit, then one new run.
- One VM run at a time on this host, through the coordinator: ask before a run starts and post when it ends. No second VM, no Molecule beside it; clean up after.
- A story whose issues are not all in Review yet still gets its script and one run; the run names the missing issue as an expected failure.

## Acceptance

- One run of `st339.sh` on the local test VM, its output naming each step passed, failed or skipped and the missing
  issue as an expected failure, and the VM cleaned up: no process of the run left, no directory of the run.

## To be checked

- Where the test app lives and which states the window can hand the screen it offers, once built; the steps follow
  what it offers.
- Whether the run's account on the VM has the Flutter of the screen's pin, the Linux desktop build's packages,
  `xvfb-run` and `dbus-run-session`.
