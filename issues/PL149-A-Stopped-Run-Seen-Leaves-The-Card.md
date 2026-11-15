# PL149 — A Stopped Run Seen Leaves The Card

```json
{
  "$schema": "issue-1.json",
  "id": "PL149",
  "title": "A Stopped Run Seen Leaves The Card",
  "state": "Implementing",
  "size": "M",
  "priority": "High",
  "rank": 69,
  "blockedBy": [],
  "covers": "A story's run the person stopped shows on its card as ended, with no task at work and no needs you once the person has seen it, the Planning count follows, and the story can be started again; Stop on a stopped run answers instead of doing nothing; the card's since is a time in the person's zone, as the window says times"
}
```

**Why this size.** M - the core's report of a stopped run and the screen package's card and count, with their tests;
the report's members stay.

**What must be true.** A person who stopped a story's work and marked its Needs you entry seen finds the card no longer
saying a task is at work or that it needs them, and the Planning count without it, and can start the story's work again from the card or Needs you.

## Plan

- Seen in a live run: after Stop and then marking the Needs you entry seen, the card still read
  "pi · 1 task · claude · claude-opus-5-5 · unattended · since 2026-11-15T15:10:51.987674543Z" and "needs you", and the
  Planning count stayed 1. The planner's work record then held the run stopped and halted, its offer still `STARTED`
  with no end, and the issue's progress `needsYou` with reason `STOPPED`.
- The card's line comes from `PlanningView._work` (`screen/lib/src/ui/planning_view.dart`), which counts every MAIN task
  of the story's reported work, whatever its state, and prints `since` as the raw wire time; "needs you" and the count
  come from `_needsYou` and the board's `needsYou` (`screen/lib/src/planning_board.dart`), which read the issue's
  `needsYou` and reason and not the rows the person put away.
- The same live run showed the dead end this leaves: the story's start stayed unavailable ("the run needs attention -
  PI68: its work was stopped, it needs you"), since the screen lets a start pass over a stopped run only with no task
  at work (`PlanningBoard._passes`), and the planner reports a task at work for every offer `STARTED`
  (`WorkAnswers.atWork`), which this offer stayed because the window never reported its end; the card's Stop work did
  nothing, since `WorkRun.stop` answers nothing for a run already stopped and the window then has nothing to end.
- The planner reports a stopped run's offers as ended, not started, once the Stop is recorded, so the work names no task
  at work and the story's start passes over the stop; a task's end reported later is still taken. The card counts only
  tasks at work, and says the run stopped where nothing is at work. Stop on a run already stopped answers that it is
  stopped, with the tasks it holds as at work, so the window ends any still running, instead of nothing.
- The window keeps what the person marked seen, as its own marks: an issue's reason as the screen's `PlanningPutAway`
  (machine, project, issue, reason, state), and a story's ended work by its end's time. The screen takes them as one
  input of the boards, `PlanningSeen`, a `Listenable` the window owns, answering whether an issue's put-away is seen and
  whether a story's end at a time is seen; the board's card "needs you" and the Planning count leave out what it
  answers seen and follow its changes. The screen keeps no copy of the marks.
- `since` is shown as the window shows times: the local time in the person's zone, with the date only when it is not
  today.
- Red first: in the core's work run tests, a stopped run's report names no started task, a Stop of a stopped run
  answers its tasks, and a new start of the story after a Stop is not refused for a task at work; in the screen's tests, a card
  of a stopped run whose row is put away shows no task and no needs you, the count drops by one, and `since` reads as a
  local time. Harm: a story that can never be started again, a person sent to a story that needs nothing, and a count that never
  clears.

## Acceptance

- The tests above, seen red against today's code; the core's and the screen's suites pass.

## To be checked

- Why the offer stayed `STARTED` after the Stop: whether the window's end of the task reached the planner after the run
  was halted, and what the planner did with it.
