# PL141 — A Stage Result Handed In Moves Its Issue

```json
{
  "$schema": "issue-1.json",
  "id": "PL141",
  "title": "A Stage Result Handed In Moves Its Issue",
  "state": "Review",
  "size": "M",
  "priority": "High",
  "rank": 3,
  "blockedBy": [],
  "covers": "The window hands in the result file an analysis or estimation task wrote, with its offer; the planner checks it, saves it through its store and only then moves the issue to the rule's done state, and a task that ends without a valid result leaves its issue in its working state with the reason on the board"
}
```

**Size:** M - one contract change the window reads, the core's run and its unit tests over fakes; the store's write is
an issue of its own.

**What must be true.** An issue stands in Analyzed or Estimated only once its plan or estimate stands in the store, and
a person sees on the board why an analysis or estimation that ended did not move its issue.

## Plan

- Today a task's ENDED with outcome DONE moves its issues to the rule's `done` state (`WorkRun`, `WorkEnds`), whatever
  the agent wrote; an analysis or estimation task writes its plan into the repository's `issues/` itself and pushes it
  through the gate, so the planner moves an issue to Analyzed whether the plan reached the central repository or not.
- A work rule names whether its stage hands back a result, and which kind: `result: ANALYSIS | ESTIMATE` beside
  `done` in the work rules the store gives (`WorkRule`, the Git store's reading of them). A rule without it works as
  today; Implementing and the readings push their work as today.
- Contract (`planner.yaml`), modeled on `handIdea`: `POST /projects/{project}/tasks/{task}/result` with the offer and
  the result as the task wrote it. The planner checks it before anything is saved - its kind matches the rule, it
  names exactly the offer's issues, a plan of 1 to 20000 characters for each, a size of the workflow's sizes, an
  estimate in whole minutes, to-be-checked and findings as bounded lists, no control or direction characters - and a
  malformed result saves nothing and is refused as `ResultRefused`, naming what failed. A valid result is saved through
  the store's stage-result write in one save, then each issue moves to the rule's `done`. One offer saves once: a second
  hand-in of a saved offer answers the saved result and writes nothing.
- Agreed with the window: it posts the result, or `{"offer", "missing": "<one line why>"}` when the task left no
  readable file, before the task's ENDED, and retries an unreached planner on later sweeps, after a restart too. A
  second hand-in of the same offer answers the first outcome - saved, or the same refusal - never a new refusal or a
  second save. A refusal is `422 ResultRefused` with `parameters.faults`, each starting with the field it names, as
  `IdeaRefused`; an unknown project `404 NoSuchProject`. The result is written through `PlanningStore.saveResult`, on
  the store as it stands when saved, each issue with `from` the working state and `to` the rule's `done`.
- The signed body bound is 266240 bytes for this path alone (the 262144-byte file and its envelope), 131072 for every
  other; agreed with the window. Each offer's outcome is kept beside the runs, compact and at most 500, so a replay is
  answered after its run was replaced too.
- The core: an ENDED DONE of a result stage moves nothing; an ENDED of a result stage whose offer has no saved result
  counts as `missing`. A result refused, or an end
  without one (the window says so when the task left no file), stops the issue in its working state under Needs you
  with one line of why, and the person may start the stage again; the store is never written.
- Red first, in `WorkRunTest` over fakes: a DONE end of an analysis rule leaves the issue In analysis; a valid hand-in
  saves once and moves it to Analyzed; a replay writes nothing; a malformed result, a result for another issue and a
  hand-in for an offer that did not end are refused and move nothing. `ApiMatchesDescriptionTest` and a controller test
  hold the new member. Harm: an issue counted analyzed or estimated whose store holds no plan, and a result saved twice.

## Acceptance

- The tests above, seen red; on the mock board an analysis task's end with a result moves its issue to Analyzed, and
  one without leaves it In analysis with the reason shown.

## To be checked

- The result file's name and JSON format, agreed with Porelo's agent, which writes the result directory, and with the
  window's agent, which reads it at the task's end and hands it in.
