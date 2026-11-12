# PL90 — A Reader Is Started With The Work It Reads

```json
{
  "$schema": "issue-1.json",
  "id": "PL90",
  "title": "A Reader Is Started With The Work It Reads",
  "state": "Review",
  "size": "M",
  "priority": "Highest",
  "rank": 1,
  "blockedBy": [],
  "covers": "A reader's order names the branch each task it reads landed on, as Porelo reports it with that task's end, and the reader is started naming those tasks, so Porelo's gate gives the reader their work to read"
}
```

**Size:** M - one contract change between the window and the planner (the task's end and a new `LANDED` event
carry where its work landed, a reader's offer the tasks it reads), carried through `core`, `app` and the screen; the
window's side and Porelo's are built in their own repositories.

**Estimate:** 67 min, the measured median of M.

**What must be true.** A reader offered after a build reads that build's work: its order names the commit the build's
work landed as and the name the reader sees it under, as Porelo reports them on the build's end, never a name the
planner makes up; and its offer names the tasks whose work it reads, so the window starts it naming them and Porelo's
gate gives the reader that work, read only. No reader is offered while the build's work still waits at the gate.

## Plan

- **The task's end names where its work landed.** `TaskEnded` gains `landed`, optional: a list of
  `{branch, ref, remote, commit}`, copied unchanged from Porelo's task row; absent or empty while the work still waits
  at the gate. `branch`, `ref` and `remote` refuse control characters (U+0000 to U+0020, U+007F to U+009F), U+2028,
  U+2029 and `~^:?*[\`, `..`, a leading `-` or `/`, a trailing `/` or `.lock`, and more than 255 code points; `ref`
  starts with `refs/`; `commit` is 40 or 64 lowercase hex digits. A malformed entry is refused (400) naming its part,
  never dropped. The list is kept on the offer's ended record (`WorkRun.Ended`).
- **No reader before the work has landed.** ENDED comes as soon as the agent ends. A build that ended done with a
  landing offers its reader at once; one without is judged as today and moves its issue to the reading state, but its
  reader waits for a new event `LANDED` (offer, landed), sent once the work has left the gate. `LANDED` for an unknown
  offer, a build that did not end done, one already landed, or one whose issue already has a later reader is refused
  (409), so a reader is offered once. A landing that can offer no reader (issue gone, rule unsound) asks the person
  (`NEXT_STEP_UNAVAILABLE`), as an end that cannot does.
- **The reader's order and offer name the build.** `{tasks}` reads, per landing, "the work of Porelo task <t>: commit
  <commit> on <remote>, after `git fetch origin`"; `PlanningStartTask` gains `reads`, the builds' task names. A reading
  taken up again takes the issue's last build with its landings (`WorkRun.lastBuild`); one with none needs the person.
  An unstarted reader of an older record is given the build it reads, or voided when none is known.
- **The screen** carries `reads` on an order, `landed` on an end and the `landed` event (`PlanningLanding`).
- **Tests, by level, red first.** Unit, core (`NextStepTest`, `WorkRunTest`); integration, app (`WorkRunHttpTest`,
  `WorkRecordTest`, `ApiMatchesDescriptionTest`); widget, screen (`planner_source_test`, `story_work_order_test`).
  Harm: a reader started on nothing, polling or reading the wrong work, offered twice, or a line injected into its
  prompt; the window and the planner reading the contract differently.

## Acceptance

- A reader offered after a build names that build's task, commit and remote-tracking name in its order and its task in
  `reads`; a build that ends before its work landed offers its reader on `LANDED`, never before; every new test seen
  red; the suite, the screen's checks and the documents' checks pass.
