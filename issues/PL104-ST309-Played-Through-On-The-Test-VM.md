# PL104 — ST309 Played Through On The Test VM

```json
{
  "$schema": "issue-1.json",
  "id": "PL104",
  "title": "ST309 Played Through On The Test VM",
  "state": "Implementing",
  "size": "M",
  "priority": "High",
  "rank": 40,
  "blockedBy": [],
  "covers": "A script plays the test of story ST309 once on the local test VM as a person would, at the command line and through the planner's API, against a store fixture on a forge of its own, and lays the shared ground every story test of this repository runs on"
}
```

**Size:** M - besides the story's own script, the shared ground the other story tests stand on: the copy to the VM,
the forge and store fixture, the planner started as the window starts it, the step report and the clean-up.

**Estimate:** 67 min, the measured median of M.

**What must be true.** The test of story ST309, "A story takes the state the operator gives it", runs once,
automated, on the local test VM the way a person plays it, as story ST347 asks of every High story: a story set to a
state its issues do not fit keeps it after a warning naming those issues, and a story with no state set takes the
earliest open state of its issues.

## Plan

- **Where.** `tool/story-tests/`: `story-test.sh`, the shared ground the other story scripts source; `st309.sh`, this
  story's steps; `run-on-vm.sh <story> --vm USER@HOST --key FILE`, run on the host. Bash, since the VM has no Java;
  `AGENTS.md`'s own part names the directory with that reason.
- **The shared ground.** `run-on-vm.sh` sends a Git bundle of the tested commit and the native executable built from
  it, unpacks both into a temporary directory of the run's account, runs the story script there and takes its report
  back. On the VM the script makes the forge - bare repositories in that directory, reached as
  `ssh://<account>@localhost/<path>` with a throwaway key it authorizes for this run alone, named to Git through a
  configuration file of its own in `GIT_CONFIG_GLOBAL`, which the planner keeps - and pushes the store fixture, a
  project and one repository shaped like `planner/app/src/test/resources/work/`, indexed by the executable's own
  `stories index` and `issues index`. It starts the planner as the window does (`lib/src/app/planner.dart` there):
  the executable with no arguments, detached, in the account's runtime directory, then reads port and token from
  `porelo-planner/planner.json` and names the project with `PUT /projects/{project}`. On exit, an interrupt too, it
  stops the planner, removes the key's line and everything it made, and names anything it left.
- **The report.** One line per step, `STEP <n> <what>: PASS`, `FAIL` with what was asked and what was got, `EXPECTED
  FAIL` or `SKIPPED` with why; exit 0 when every step passed or failed as expected. The expected failures come on the
  command line as `--expect <step>=<issue>`, read from this issue, so the script cites no issue.
- **The steps**, as the operator plays them; a fixture story stands in for ST25:
  1. At the command line in a clone of the project, `stories index stories` keeps the story's stated Backlog while
     one issue is Implementing and one Finished, and prints the warning naming both.
  2. Through the planner, as the window saves: the move of a second story to a state its issues do not fit is
     answered `409 StoryStateMismatch` naming each issue that does not fit; the same move with `confirm` saves it.
  3. The board (`GET /projects/{p}/stories`) shows the story in that state with `stateGiven`, and says which issues
     do not fit it.
  4. In a fresh clone the story's head and `stories/README.md` hold the state, and the issues kept theirs.
  5. A story whose head states none stands in the earliest open state of its issues, on the board and in the index.
  6. A state written into a story's head by hand and pushed stands on the board after `POST /refresh`.
- **This repository's issues of the story not in Review:** [PL84](README.md#pl84) (the board's answer naming the
  issues that do not fit, step 3) and [PL45](README.md#pl45) (a story moving with its issues, no step of its own):
  the run is started with `--expect 3=PL84`, and a failing step is read against [PL45](README.md#pl45) before it is taken as a fault.

**Rules for this story test (operator)**

- A test script is test tooling, not production code: no tests for the test scripts, no red-first, no review loops (one Sonnet reading at most). The script is proven by its run on the VM.
- On the VM a failing script is fixed ad hoc on the spot and the run carries on; every fault found (in the script or in the product) is collected, not fixed one by one. After the run: product faults become estimated Backlog issues, script fixes go into one commit, then one new run.
- One VM run at a time on this host, through the coordinator: ask before a run starts and post when it ends. No second VM, no Molecule beside it; clean up after.
- A story whose issues are not all in Review yet still gets its script and one run; the run names the missing issue as an expected failure.

## Acceptance

- One run of `st309.sh` on the local test VM, its output naming each step passed or failed and the missing issue as
  an expected failure, and the VM left as before: no planner running, no key authorized, no directory of the run.

## To be checked

- Whether moving a story moves its least advanced issues first (`Moves` does so today), so step 2 must take a
  story whose issues stay where they are, or sets the state in the head instead.
- Whether a story-2 head may leave out `state` (step 5), and whether `issues check` wants a stage record in
  `stats/` for the fixture's issue in Implementing.
- Which VM account the runs use, since the planner's agent has none of its own, and whether that account's `sshd`
  takes a key it adds for itself on localhost, with `git`, `curl` and `jq` installed.
