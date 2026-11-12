# PL49 — Work Moves On By Itself From A Move On The Board

```json
{
  "$schema": "issue-1.json",
  "id": "PL49",
  "title": "Work Moves On By Itself From A Move On The Board",
  "state": "Review",
  "size": "L",
  "priority": "High",
  "rank": 25,
  "blockedBy": [
    "PL64",
    "PL65"
  ],
  "covers": "Moving an issue or a story into a working state on the board opens the Start dialog filled with the project's defaults, one tab per task, and the chain the work rules give - build, reading, review - runs on by itself, asking the person where a reading blocks; a reading can be started by hand; the model and effort follow the task's phase and the issue's risk"
}
```

**Size:** L - a move that starts work crosses the core's proposal, the board's contract and the screen; beside it a
reading started by hand.

**Estimate:** 113 min, the measured median of L. Uncertain: the two parts sum to 100 minutes by the medians, between M
and L; the issue waits on two others of this repository.

**What must be true.** A person moves an Agent issue, or a story, into a working state on the board, and the planner
does the rest as the project's work rules say: the start dialog opens filled with the rule's first mode and the first
agent's model, the builder starts with that state's instructions; work that ends done goes to Reading and a reader
starts by itself; a reading without blocking findings goes to Review, and a reading with them asks the person.

## Plan

What stands today, read in the code:

- `stories/work.yml` in the project gives each working state its `from`, `done`, `then`, `blocked`, `per`, `modes`,
  `order` and `models`; `WorkRules` keeps the sound ones. The project's rules now include In analysis (from Idea and
  Accepted), Under estimation (from Analyzed) and Reading from Review too.
- A story dropped on a working state already opens the start dialog (`planning_view.dart`, `_start` with the target),
  which preselects the proposal's first mode and the first agent's model. But the proposal (`WorkProposal.propose`)
  names every open issue of the story, whatever its state, and the start (`NextStep.started`) refuses an issue whose
  state the rule's `from` does not hold, so a story whose issues stand in different states cannot be started.
- An issue cannot be moved into a working state: the screen stages it as a move, and `Moves.check` refuses it
  (`EnteredOnlyByWork`). The proposal and the start take a whole story, never chosen issues.
- A Reading cannot be started by hand: `WorkProposal.propose` refuses every rule with `blocked` ("Readings are offered
  only by a run"), though the Reading rule now enters from Review.
- Once started, the chain runs by itself: a task's end is reported (`TaskEventRequest`), `WorkEnds` judges it,
  `NextStep` plans the next state and tasks along `then`, and the screen starts them at once (`task_runs.dart`,
  `follow`). How a blocking reading and a task's mode are handled changes with
  [PL64](README.md#pl64): a blocking reading asks the person instead of starting Reworking, and a task that starts by
  itself takes the rule's first mode. A Manual issue gets no task in any start ([PL65](README.md#pl65), on the issue's
  worker field).

What is missing:

1. **A move opens the Start for what it moves** (M). The proposal and the start take an optional list of the story's
   issues; a move of an issue into a working state opens the start dialog for that issue alone, and cancelling the
   dialog cancels the move. A story's proposal names its least advanced open issues whose state the rule's `from`
   holds, as `Moves.check` moves a story's least advanced issues, one tab per task. A change to the window's
   contract (`StoryWorkProposalRequest`, `StoryWorkRequest`), the mock mode's answer and the screen's issue move
   action. Red first, unit, in `WorkProposalTest`: a story with an issue in Backlog and one in Review, proposed for
   Implementing, names only the Backlog issue; a proposal for one named issue names it alone.
2. **A reading started by hand** (S). A proposal for a rule with `blocked` offers one reader per issue, each issue
   alone, with the readings counted on from the issue's run. Red first, unit, in `WorkProposalTest`: a Reading for an
   issue in Review offers one reader for it; in `NextStepTest`: its clean end leads to Review.
Not part of this issue: model and effort chosen by phase and risk, an issue of its own
([PL66](README.md#pl66)); a reading limit in the work rules, since the work rules set no cap on reading
rounds for now and the start dialog's reading rounds stay as they are; and asking to stop a running task before a
move out of a working state, which belongs to the warnings before a move ([PL38](README.md#pl38)).

What a caller could do that the plan above did not forbid, read in the code, and what forbids it now:

- **A named issue list is checked whole before any proposal or order.** It is present and non-empty or absent, each
  issue named once, each an open issue of the story (or a sub-issue of one), each worked by an agent, and each in a
  state the rule's `from` holds; anything else is refused before a task is offered, never only at STARTED, where
  `NextStep.started` also takes an issue already in the target state. Red first, unit, in `WorkProposalTest`.
- **A reading by hand reads the issue's last landed build, or is refused.** `WorkOrder.accept` builds a `MAIN` task
  and `WorkRun.order` a task without `reads`, so a reading through that path would end as a build: counted as no
  reading and moved to Review whatever it found. For a rule with `blocked`, `WorkRun.order` offers one `READER` per
  issue through `NextStep.task`, reading the last build of that issue in the story's recorded run; an issue without
  one is refused ("No landed build of the issue is known to read"), and its counted readings go on from the run.
  Red first, unit, in `WorkRunTest`: a hand reading's offer reads the last build; its blocking end stops in Reading
  under Needs you and its clean end leads to Review; one with no known build is refused and records nothing.
- **An order for chosen issues never drops work the run still owes.** `WorkRun.order` replaces the story's run with
  its offers. An order is refused while the run holds an offer not yet started that an earlier end offered (a
  reader or a rework waiting for the window), so starting issue B cannot drop issue A's reader. A build's history
  older than the run it was ordered in is not kept: a hand reading of such an issue is refused, as above. Red first,
  unit, in `WorkRunTest`.
- **Cancelling the dialog cancels only what has not started.** An issue's move into a working state is its start:
  nothing is staged, and the issue stands in the working state once its task started. Cancel before any start
  leaves the issue where it stood; after a start the dialog says the task runs, and Stop work is the way back.

What the build found, read in the code:

- **A story's proposal names only the least advanced of its open Agent issues whose state the rule's `from` holds.**
  A story whose working state a person set and whose issues stand past it is offered nothing to rework there, and
  when Agent issues exist but none stands where the rule takes from, the proposal is refused ("No open issue of the
  story stands where the rule takes from.").
- **A reading's offer has the role `READER`**, not `MAIN`: the board's contract allows both for the main task of a
  proposal and an order, and the screen expects one reader per issue for a reading, each with no helpers.
- **An issue's start opens from its state menu on the story**, as "Start <state>…" for each working state, offered
  for an Agent issue only; a Manual issue keeps its move into a working state by hand.
- **The screen's `PlanningSource.proposeStoryWork` takes an optional named `issues`**, so every class that implements
  or extends a planning source declares it; the screen's own fakes do.

- **The code reading found two gaps, both fixed.** An offer a person's choice made was not counted as waiting, so an
  order could replace the run and drop it; and a reading by hand left no build in the run it started, so another round
  or a rework of it found nothing to read and a failed choice cleared the stop.

Order: 2, 1. Sum by the medians: 1 M, 1 S = 67 + 33 = 100 minutes, an L.

## Acceptance

- With today's work rules: an Agent issue moved to Implementing opens the start dialog for it alone, with the first
  mode and model preselected, and its builder starts on confirmation; an end done sets Reading and starts the reader
  in its rule's first mode; a reading without blocking findings sets Review; one with blocking findings stays in
  Reading and asks the person. A story with issues in Backlog and Review dropped on Implementing starts only the
  Backlog issue. An issue in Review can be read again by hand. A Manual issue moved the same way gets no task.

Work moves on only while a window is open: the window, not the planner, starts every task with Porelo's `Start`, and
reports each end. When no window was open as a task ended, the board is read again and the next step taken once a
window starts again; the planner as a service of the system does not change that, since it starts no task itself.
