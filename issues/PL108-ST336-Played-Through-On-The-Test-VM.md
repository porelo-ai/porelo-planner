# PL108 — ST336 Played Through On The Test VM

```json
{
  "$schema": "issue-1.json",
  "id": "PL108",
  "title": "ST336 Played Through On The Test VM",
  "state": "Backlog",
  "size": "S",
  "priority": "High",
  "rank": 44,
  "blockedBy": ["PL104", "PL105"],
  "covers": "A script plays the test of story ST336 once on the local test VM through the planning screen against the real planner, as a person opens a story with a long Markdown description and many issues"
}
```

**Size:** S - one screen driver test, a fixture story and its script on the story tests' shared ground and screen
driver, with one run on the VM.

**Estimate:** 33 min, the measured median of S.

**What must be true.** The test of story ST336, "A story's page shows its description and issues well", runs once,
automated, on the local test VM the way a person plays it on a story's page, as story ST347 asks of every High
story: a long Markdown description is rendered as Markdown and folds, and the issue list searches, filters and sorts
and keeps that when the person comes back.

## Plan

- **Where.** `tool/story-tests/st336.sh` and `screen/story_test/st336_test.dart`, on the shared ground of
  [PL104](README.md#pl104) and the screen driver of [PL105](README.md#pl105), which mounts `PlanningScreen` against
  the planner as the window does and runs under `flutter test` on the VM.
- **The fixture.** One story whose text above `## Data` is long Markdown - headings, a list, emphasis, a code
  block, a link - and which names some thirty issues over two repositories, in several states and of several sizes.
  The planner serves the text as `description` (`GET /projects/{p}/stories/{s}`).
- **The steps**, as the operator plays them on the story's page:
  1. The story opens from the board.
  2. The description shows as Markdown: a heading as a heading, the list as a list, no `#` or `*` left as text.
  3. The description folds away and unfolds again.
  4. The issue list's search narrows it to the issues whose title holds the word typed.
  5. A filter by state narrows it, and a sort by number and by size orders it.
  6. Back on the board and into the story again, the search, filter and sort stand as left.
- **This repository's issues of the story not in Review:** [PL36](README.md#pl36) (a description shown in its own
  format) and [PL37](README.md#pl37) (a folding description and a searchable issue list), both Backlog. The run is
  started with `--expect 2=PL36`, `--expect 3=PL37`, `--expect 4=PL37`, `--expect 5=PL37` and `--expect 6=PL37`.

**Rules for this story test (operator)**

- A test script is test tooling, not production code: no tests for the test scripts, no red-first, no review loops (one Sonnet reading at most). The script is proven by its run on the VM.
- On the VM a failing script is fixed ad hoc on the spot and the run carries on; every fault found (in the script or in the product) is collected, not fixed one by one. After the run: product faults become estimated Backlog issues, script fixes go into one commit, then one new run.
- One VM run at a time on this host, through the coordinator: ask before a run starts and post when it ends. No second VM, no Molecule beside it; clean up after.
- A story whose issues are not all in Review yet still gets its script and one run; the run names the missing issue as an expected failure.

## Acceptance

- One run of `st336.sh` on the local test VM, its output naming each step passed or failed and the missing issues as
  expected failures, and the VM cleaned up: no planner running, no key authorized, no directory of the run.

## To be checked

- Which widgets the Markdown view, the fold and the list's search, filter and sort will be once built, so the driver
  finds them; until then it looks by the words a person reads, and a step that finds none fails as expected.
- Whether "comes back" is a return to the board within one screen, or a new screen after the window was closed;
  the driver plays the first, and the second if the built issue keeps the list's settings across a start.
