# PL104 — ST309 Played Through On The Test VM

```json
{
  "$schema": "issue-1.json",
  "id": "PL104",
  "title": "ST309 Played Through On The Test VM",
  "state": "Review",
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
- **Built.** `tool/story-tests/story-test.sh`, `st309.sh` and `run-on-vm.sh`, as above, with these findings from the
  code: the planner's Git calls set their own `GIT_SSH_COMMAND`, so the run's key reaches them through an `ssh-agent`
  of the run's own, and localhost's host key is added to the account's `known_hosts` as one marked line, removed again; the
  key's line in the account's `authorized_keys` is `restrict,from="127.0.0.1,::1"` and marked the same way. The planner's
  clones and log go under the run's directory through `XDG_CACHE_HOME` and `XDG_STATE_HOME`. A run refuses an
  account with a planner running or a marked line or run directory left, and `story-test.sh leftovers` names them.
  A story's move moves its least advanced issues (`Moves`), so in step 2 the issue in Backlog goes along to Review and
  step 4 checks that the issue that does not fit kept its state. A story-2 head may leave out `state`, and
  `issues check` takes an issue in Implementing without a stage record: the fixture and step 1 were run locally with
  the executable, without a VM. `stories/work.yml` must be in the fixture, since without it the planner refuses every
  move.
- **Found by the first VM run, a fault of the script.** The planner answers a save once its commits stand in its own
  clones and pushes them in the background, the project's own repository last, and fetches no repository over a save
  not pushed yet. A fresh clone made right after the answer, or a head pushed by hand meanwhile, met the forge before
  the planner's push: step 4 found no state in the story's head. Played locally with the stack's executable and an
  `ssh` on `PATH` that runs Git's pack command in place, both stories failed so and both passed once `st_clones` waits,
  up to 60 s, until the planner's clones hold no save waiting (`refs/planner/local`); the steps still judge only the
  fresh clone. `st_finish` now passes the token to `grep` with `-e`, since a token starting with `-` was read as an
  option.

**Rules for this story test (operator)**

- A test script is test tooling, not production code: no tests for the test scripts, no red-first, no review loops (one Sonnet reading at most). The script is proven by its run on the VM.
- On the VM a failing script is fixed ad hoc on the spot and the run carries on; every fault found (in the script or in the product) is collected, not fixed one by one. After the run: product faults become estimated Backlog issues, script fixes go into one commit, then one new run.
- One VM run at a time on this host, through the coordinator: ask before a run starts and post when it ends. No second VM, no Molecule beside it; clean up after.
- A story whose issues are not all in Review yet still gets its script and one run; the run names the missing issue as an expected failure.

## Acceptance

- One run of `st309.sh` on the local test VM, its output naming each step passed or failed and the missing issue as
  an expected failure, and the VM left as before: no planner running, no key authorized, no directory of the run.

## To be checked

- On the VM: whether the `planner` account's `sshd` takes a key the run adds for itself on localhost, and whether
  `git`, `curl`, `jq`, `ssh-agent` and `flock` are installed; the run says so at its start if not.
