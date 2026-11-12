# PL85 — The Planner In A Real Run From Idea To Finished

```json
{
  "$schema": "issue-1.json",
  "id": "PL85",
  "title": "The Planner In A Real Run From Idea To Finished",
  "state": "Implementing",
  "size": "M",
  "priority": "High",
  "rank": 1,
  "blockedBy": [],
  "covers": "On a local VM the real planner beside a real installed Porelo carries one story from a discussed idea to Finished with one real, low-cost agent task in the test organization, and a read-only probe reads each of the planner's own moves from its API, the store's files and the forge"
}
```

**Size:** M - a probe script and its part of one acceptance run on a local VM; no code of the planner and no
contract changes.

**Estimate:** 67 min, the measured median of M. Uncertain: the run waits on the window's steps and one real agent
task.

**What must be true.** On a local VM, with a real installed Porelo and the real planner, one story goes the whole way
a person takes it - an idea discussed and handed in, moved through the planning states, built by one real, low-cost
agent task in the GitHub test organization, read, and set Finished - and every move the planner makes by itself, and
every task's start and result, is seen in its effect: in the planner's REST API, in the store's files in Git and on
the forge. Nothing in the run is faked.

## Plan

Read in today's code. The window drives every step a person takes; the planner runs in the same account on the VM,
named a project by `PUT /projects/{project}` with the project repository's clone URL, and found by the window
through its address file (`porelo-planner/planner.json` in the account's runtime directory: port and bearer token).

What the planner does in the run, and where:

1. **The idea.** `POST /projects/{p}/ideas/discussion` answers one `DISCUSSION` task for Porelo's `Start`
   (`IdeaDiscussion`); it records and saves nothing. When the window hands in the idea file the task wrote,
   `POST /projects/{p}/ideas` saves, once per offer (`HandedIdeas`, `NewIdeas`), one story in state Idea in the
   project's own repository and an issue in Idea in each repository the idea names, with the stories index and each
   issue index rewritten, one commit per repository pushed under the person's Git login.
2. **The person's moves.** Idea to Backlog goes through `POST /projects/{p}/moves` (`Moves`); a story moves its
   least advanced issues, and a working state is not entered this way by an issue an agent works.
3. **The task's start.** `POST /stories/{s}/proposal` and `POST /stories/{s}/work` (`WorkProposal`, `WorkOrder`,
   `WorkRun.order`) answer the tasks to start, from the work rules in the project repository's `stories/work.yml`;
   the window starts each with Porelo's `Start` and reports `STARTED` on `POST /tasks/{task}/events`. Only then does
   the planner move the task's issues into the rule's working state, Implementing (`NextStep.started`), saved like a
   move.
4. **The result coming back.** The window reports `ENDED` with Porelo's ended record (finished, why, the agent's
   answer, unpushed commits). `WorkEnds.judge` judges it and `NextStep.after` moves the issues to the rule's next
   state - the `then` state, else `done` - and offers the next task: after the build a `READER` in Reading. A reader's
   answer must end with one `Findings:` block; without a blocking finding `ReadingCycle` moves the issue to the
   Reading rule's done state, Review; with one, or a malformed block, it stops and raises the issue (`needsYou`,
   `attention`). A story without a state of its own follows its issues.
5. **Finished.** The person moves the story or issue from Review to Finished through `POST /moves`.

How each effect is read, all without changing anything:

- **API:** `GET /projects/{p}/workflow` (faults empty), `/stories`, `/stories/{s}`, `/stories/{s}/issues`,
  `/issues/{i}` (state, `needsYou`, `attention`, `atWork`), `/stories/{s}/work` (each task's role, state, done,
  agent, model, `atWork` and `ended`), and the `board` events of `GET /projects/{p}/events`.
- **Store:** on the branch's tip, the issue file's JSON head `state`; the last line of `stats/<issue>.jsonl`, which
  every save that changes an issue's state appends (`status_after`); the story file's issue line `(<state>)` and its
  row in `stories/README.md`; each planning commit's subject ending in `[skip ci]`.
- **Forge:** the branch's tip before and after the task, and the agent's commits between them; Porelo's own record
  of the task through `porelo task records -p <project> --since <time> --json`.

Steps:

1. `tool/planner-probe.sh`, bash: one read-only check per call (`health`, `faults`, `story-id`, `story-state`,
   `issue-state`, `issues`, `work`, `attention`, `events`, `store-issue`, `store-story`, `forge-head`,
   `forge-moved`, `records`), the planner's address and token from the address file or the environment, the token
   handed to curl on its standard input; exit 0 when the check holds, 1 when it does not, printing what was asked and
   what was got, and 2 when it cannot run. The repository's own part of `AGENTS.md` names the script with its reason:
   it runs on the VM beside the native planner, where there is no Java.
2. The planner's part of the run's guide: after each step the window takes, the probe call that reads its effect and
   the answer it must give.
3. The run itself on the VM, in the account the window runs in, with the native planner of the commit under test.

Test levels: no unit test (a test script, no code of the planner changes); the proof is the run on a local VM.

## Acceptance

- On the VM, after each step of the run, the probe's check for that step exits 0, and the run's log keeps every
  probe line: the story in Idea and its issues written; the moves to Backlog; Implementing only after `STARTED`;
  Reading after the build's `ENDED` with a reader offered; Review after a reading without a blocking finding; Finished
  after the person's move; each state in the API, in the issue's file, its stage records and the story's file alike,
  each planning commit ending in `[skip ci]`, and the agent's commits on the forge.
- Seen failing first: the probe's state check against a state the board does not show exits 1 and prints both.

## To be checked

- Which work rules the test project's `stories/work.yml` gives Implementing and Reading (`then`, `done`, the first
  agent and model), so the cheapest model is the default or chosen in the start dialog.
- Whether `jq` and `git` are installed in the VM account the run uses.
