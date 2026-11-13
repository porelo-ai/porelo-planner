# PL114 — A Failed Or Blocked Task Offers A Way On

```json
{
  "$schema": "issue-1.json",
  "id": "PL114",
  "title": "A Failed Or Blocked Task Offers A Way On",
  "state": "Review",
  "size": "L",
  "priority": "High",
  "rank": 50,
  "blockedBy": ["PL77"],
  "covers": "The Needs-you row of an issue whose task ended FAILED offers Start again and Move on, and one that ended BLOCKED offers Move on, so the person is not left with a move by hand alone"
}
```

**Size:** L - one more reason through the pass-over that [PL77](README.md#pl77) builds, a failed or blocked reader
stopping its reading as an unreadable one does, a guard for a failed builder's unpushed commits, in core, the
contract's enum and the screen's rows, with their tests; one contract change, agreed with the window.

**Estimate:** 67 min, the measured median of M.

**What must be true.** A person whose builder or reader ended `FAILED` or `BLOCKED` can go on from the row in
*Needs you*: start the work again once they looked at why, or move the issue on. Today a builder that ran out of time
ends `FAILED`; the story's Start is held while the run needs attention, the row offers only "Put away", and the person
has no way on but a move by hand.

## Plan

- **Today, read in the code.** A task's end `FAILED` or `BLOCKED` leaves its issues in their state under *Needs you*
  (`planner.yaml`, the task end's `outcome`). [PL77](README.md#pl77) lets a row's Start pass over
  `NEXT_STEP_UNAVAILABLE`, `SAVE_REFUSED` and `STOPPED`, bound to the run's id and the board revision; it keeps
  `FAILED` with its own actions, Stop and Put away, and no Start, and has no rule for `BLOCKED`.
- **A builder's row.** `passOver.reason` also takes `FAILED`, under [PL77](README.md#pl77)'s rules unchanged: the
  run's id and the revision as the row showed them, no task at work, and no other issue of the run holding another
  reason, refused with `RunChanged` otherwise. A task's end puts its reason on every issue it covered, so a Start over
  one of them starts the run again for all of them; the screen offers it only where every raised row holds the same
  reason, as today. `WorkRun.PASSABLE` and the screen's `PlanningPassOver.reasons` change together and keep `BLOCKED`
  out. A `FAILED` builder's row offers Start again, Move on and Put away. Any row's Start again is unavailable, saying
  why, while the last ended task of an issue the run raised reports unpushed commits, or does not say how many, since
  only that task can push them and a Stop renames a failure STOPPED; a new builder never starts beside work that would
  be lost. The planner refuses such a Start over any reason too, with `WorkNotAllowed`. Move on from such a row says
  that the task's commits stay unpushed. A `BLOCKED` builder's row offers
  Move on and Put away but no Start: a blocked task said it cannot go on unless something changes. Stop stays on both
  rows, as on every row of an ended run with reasons.
- **Again where it stands.** A failed builder's issue stands in the working state the row's Start leads to, and a
  rule's `from` need not name that state, so a Start naming the issue was refused "The rule does not allow work from
  this state". An issue a Start names standing in the target state itself is worked there again only where the run
  raised it with the reason the Start passes over, or, for a proposal, which writes nothing, with any reason a Start
  may pass over (`ChosenIssues`); a Start without a pass-over, or naming an issue the run did not raise, is refused by
  the rule as before.
- **A reader's row.** A reader that ends `FAILED` or `BLOCKED` stops its reading as one whose findings could not be
  read: no round counted, no findings kept, the reason `FAILED` or `BLOCKED`. Its row then offers Read again and Review
  anyway, and no Rework, through `chooseReading` as today, so Start never starts a builder for an issue standing in a
  reading state.
- **Move on** keeps the label that names where it leads, and makes that move as a move on the board is made, saved at
  once; it is the person's own move.
- **The task's words.** The row shows the ended task's last words on why it ended as plain text, cut to a few lines,
  and says that the task gave none when it gave none.
- **Contract.** `passOver.reason`'s enum gains `FAILED`, agreed with the window: it passes it through unread, and the
  new keys follow `story-work-start <issue>`, `story-work-move-on <issue>` and `story-work-put-away <issue>`. The words
  for `FAILED` and `BLOCKED` under the attention reason say that a reader's stops its reading.
- **Tests, red first.**
  - Unit, core: a Start passing over a builder's `FAILED` starts the run again, for every issue the task covered; one
    while another issue of the run holds another reason, or a task is at work, is a conflict; a Start passing over
    `BLOCKED` is refused. Harm: an agent started over a failure nobody looked at, or over a block the task named.
  - Unit, core: a reader ending `FAILED` or `BLOCKED` stops its reading with no round and no findings; Read again
    offers a reader of the same build; a Rework is refused. Harm: a builder started for an issue standing in a reading.
  - Widget, screen: a `FAILED` builder's row offers Start again and Move on, and Start again is unavailable with
    unpushed commits; a `BLOCKED` builder's row offers Move on but no Start; a reader's `FAILED` row offers Read again
    and Review anyway and no Start; a `ROUND_LIMIT` row offers no Start. Harm: a row that offers the wrong way on.

## Acceptance

- From a `FAILED` row the person starts the work again or moves the issue on, and from a `BLOCKED` row moves it on,
  without a move by hand; a Start from a row passes over only that reason and is refused when it changed.

