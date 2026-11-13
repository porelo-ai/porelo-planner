# PL106 — ST315 Played Through On The Test VM

```json
{
  "$schema": "issue-1.json",
  "id": "PL106",
  "title": "ST315 Played Through On The Test VM",
  "state": "Review",
  "size": "S",
  "priority": "High",
  "rank": 42,
  "blockedBy": ["PL104"],
  "covers": "A script plays the test of story ST315 once on the local test VM as a person would, through the planner's API and at the command line, in a repository with no story or issue tooling in its build"
}
```

**Size:** S - one script on the shared ground of the story tests, its steps HTTP calls, Git and the executable's own
commands, with one run on the VM.

**Estimate:** 33 min, the measured median of S.

**What must be true.** The test of story ST315, "The planner's Git adapter alone handles stories and issues", runs
once, automated, on the local test VM the way a person plays it, as story ST347 asks of every High story: in a
repository whose build has no story or issue tooling, an issue's state changed and an idea added through the planner
are written and checked by the planner's Git store alone, and a broken head shows as a fault on the board.

## Plan

- **Where.** `tool/story-tests/st315.sh`, on the shared ground of [PL104](README.md#pl104): the forge, the store
  fixture, the planner started as the window starts it, the step report and the clean-up.
- **How it plays.** As the window does, over HTTP against the planner with the token from its address file, and at
  the command line in clones of the fixture's forge with the native executable of the bundle.
- **The steps**, as the operator plays them:
  1. The fixture's repository has no build of its own - no `pom.xml`, wrapper or other build file - only its
     `issues/`.
  2. An issue's state is changed with `POST /projects/{p}/moves`, from one state outside the working states to
     another, as the board saves it.
  3. An idea is added with `POST /projects/{p}/issues`, as the window's New issue dialog hands it in.
  4. In a fresh clone: the moved issue's head holds its new state and its stage record its move; the new issue's
     file stands in Idea under the next free number; `issues/README.md` holds both rows; each planning commit ends
     in `[skip ci]`; and `issues check issues` of the executable prints OK there.
  5. A head broken by hand - a state the workflow does not know - is committed and pushed as a person would; after
     `POST /refresh` the board's answer names that file as a fault, and the rest of the board still stands.
  6. That porelo-buildtools and every repository build without the story and issue commands is not played here: it
     is their builds' to show, and the step prints `SKIPPED` with that reason.
- **This repository's issues of the story not in Review:** [PL14](README.md#pl14) (local paths as repositories),
  [PL62](README.md#pl62) (the Git store's refusal texts) and [PL15](README.md#pl15) (answer types generated from the
  description). No step is known to need them; the run names them with `--expect` as expected failures of the story,
  and a failing step is read against them before it is taken as a fault.
- **Built.** `tool/story-tests/st315.sh` on the shared ground, and `st_skip` added to `story-test.sh` for a step a
  run names but does not play. Step 2 moves AB01 from Analyzed to Estimated, two planning states, since a working
  state is entered only by work or by an issue worked by hand; every move of a top-level issue writes its
  `stats/<issue>.jsonl` line, which step 4 reads. A head in a state the workflow does not know leaves its issue out
  and names it as a fault `<repository>: issues/<file>: ...` in `GET /projects/{p}/workflow`'s `faults`, which step 5
  reads, while `GET /stories` still answers the story with its other issue. The repository a new issue goes to is
  named by the last part of its clone URL, `st-alpha`. The fixture, step 1 and step 5's edit were run locally with the
  executable and `file://` remotes, without a VM: the fixture's checks pass and `issues check` refuses the edited
  head.

**Rules for this story test (operator)**

- A test script is test tooling, not production code: no tests for the test scripts, no red-first, no review loops (one Sonnet reading at most). The script is proven by its run on the VM.
- On the VM a failing script is fixed ad hoc on the spot and the run carries on; every fault found (in the script or in the product) is collected, not fixed one by one. After the run: product faults become estimated Backlog issues, script fixes go into one commit, then one new run.
- One VM run at a time on this host, through the coordinator: ask before a run starts and post when it ends. No second VM, no Molecule beside it; clean up after.
- A story whose issues are not all in Review yet still gets its script and one run; the run names the missing issue as an expected failure.

## Acceptance

- One run of `st315.sh` on the local test VM, its output naming each step passed, failed or skipped and the missing
  issues as expected failures, and the VM cleaned up: no planner running, no key authorized, no directory of the run.
