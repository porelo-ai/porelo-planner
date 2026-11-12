# PL107 — ST316 Played Through On The Test VM

```json
{
  "$schema": "issue-1.json",
  "id": "PL107",
  "title": "ST316 Played Through On The Test VM",
  "state": "Backlog",
  "size": "S",
  "priority": "High",
  "rank": 43,
  "blockedBy": ["PL104", "PL105"],
  "covers": "A script plays the test of story ST316 once on the local test VM through the planning screen against the real planner, as a person reads sizes and folds a state's box and a story card"
}
```

**Size:** S - one screen driver test and its script on the story tests' shared ground and screen driver, with one run
on the VM.

**Estimate:** 33 min, the measured median of S.

**What must be true.** The test of story ST316, "Visual adjustments in the planner", runs once, automated, on the
local test VM the way a person plays it on the planning board, as story ST347 asks of every High story: every story
card and issue tile shows its size beside its priority, a folded state's box hides its tiles and keeps its header,
count and the column's width, and a folded story card leaves only its heading.

## Plan

- **Where.** `tool/story-tests/st316.sh` and `screen/story_test/st316_test.dart`, on the shared ground of
  [PL104](README.md#pl104) and the screen driver of [PL105](README.md#pl105), which mounts `PlanningScreen` against
  the planner as the window does and runs under `flutter test` on the VM.
- **The fixture.** Stories and issues of every size, XS to XL, several of them in Estimated, so the box has tiles to
  hide; a story's size in its head once the store carries one.
- **The steps**, as the operator plays them on the board:
  1. Every story card and every issue tile shows its size, XS to XL, beside its priority.
  2. Folding the box "Estimated" hides its tiles; its header and count stay, and the column's width, measured
     before and after, is the same.
  3. The column's own chevron (`planning-fold-phase <phase>`) still narrows the column.
  4. Folding one story card leaves only its heading with number and priority; the cards beside it stay open.
- **This repository's issues of the story not in Review:** [PL47](README.md#pl47) (a story's size beside its
  priority, Implementing) and [PL48](README.md#pl48) (sizes, folded boxes and folded cards, Backlog). The run is
  started with `--expect 1=PL47`, `--expect 2=PL48` and `--expect 4=PL48`; step 3 plays what the board has today.

**Rules for this story test (operator)**

- A test script is test tooling, not production code: no tests for the test scripts, no red-first, no review loops (one Sonnet reading at most). The script is proven by its run on the VM.
- On the VM a failing script is fixed ad hoc on the spot and the run carries on; every fault found (in the script or in the product) is collected, not fixed one by one. After the run: product faults become estimated Backlog issues, script fixes go into one commit, then one new run.
- One VM run at a time on this host, through the coordinator: ask before a run starts and post when it ends. No second VM, no Molecule beside it; clean up after.
- A story whose issues are not all in Review yet still gets its script and one run; the run names the missing issue as an expected failure.

## Acceptance

- One run of `st316.sh` on the local test VM, its output naming each step passed or failed and the missing issues as
  expected failures, and the VM cleaned up: no planner running, no key authorized, no directory of the run.

## To be checked

- Which keys or texts the box fold and the card fold will carry once built, so the driver finds them; until then
  the driver looks for them by the words a person reads, and a step that finds none fails as expected.
- How a story's size is written in its head once the store carries it, for the fixture.
