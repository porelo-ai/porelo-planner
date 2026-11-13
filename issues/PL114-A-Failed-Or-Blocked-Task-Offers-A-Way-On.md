# PL114 — A Failed Or Blocked Task Offers A Way On

```json
{
  "$schema": "issue-1.json",
  "id": "PL114",
  "title": "A Failed Or Blocked Task Offers A Way On",
  "state": "Backlog",
  "size": "M",
  "priority": "High",
  "rank": 50,
  "blockedBy": ["PL77"],
  "covers": "The Needs-you row of an issue whose task ended FAILED offers Start again and Move on, and one that ended BLOCKED offers Move on, so the person is not left with a move by hand alone"
}
```

**Size:** M - one more reason through the pass-over that [PL77](README.md#pl77) builds, and Move on for two, in core,
the contract's enum and the screen's rows, with their tests; one contract change agreed with the window first.

**Estimate:** 67 min, the measured median of M.

**What must be true.** A person whose builder or reader ended `FAILED` or `BLOCKED` can go on from the row in
*Needs you*: start the work again once they looked at why, or move the issue on. Today a builder that ran out of time
ends `FAILED`; the story's Start is held while the run needs attention, the row offers only "Put away", and the person
has no way on but a move by hand.

## Plan

- **Today, read in the code.** A task's end `FAILED` or `BLOCKED` leaves its issues in their state under *Needs you*
  (`planner.yaml`, the task end's `outcome`). [PL77](README.md#pl77) lets a row's Start pass over
  `NEXT_STEP_UNAVAILABLE`, `SAVE_REFUSED` and `STOPPED`, bound to the run's id and the board revision; it keeps
  `FAILED` with its own actions and no Start, and has no rule for `BLOCKED`.
- **The change.** `passOver.reason` also takes `FAILED`, under [PL77](README.md#pl77)'s rules unchanged: only that
  issue's reason, no other issue's reason in the run, no task at work, the run's id and the revision as the row showed
  them, refused with `RunChanged` otherwise. A `FAILED` row offers Start again and Move on, beside Put away, and shows
  the task's own words on why it ended before the person starts it again. A `BLOCKED` row offers Move on and Put away
  but no Start: a blocked task said it cannot go on unless something changes, so starting the same work again would only
  repeat it.
- **Contract.** `passOver.reason`'s enum gains `FAILED`, agreed with the window before it is built; the
  window passes it through unread.
- **Tests, red first.**
  - Unit, core: a Start passing over `FAILED` starts the story again; one over `FAILED` while another issue of the run
    is raised, or a task is at work, is a conflict. Harm: an agent started over a failure on another issue nobody
    looked at.
  - Widget, screen: a `FAILED` row offers Start again and Move on and sends the pass-over; a `BLOCKED` row offers
    Move on but no Start; a `ROUND_LIMIT` row offers no Start. Harm: a row that offers the wrong way on.

## Acceptance

- From a `FAILED` row the person starts the work again or moves the issue on, and from a `BLOCKED` row moves it on,
  without a move by hand; a Start from a row passes over only that reason and is refused when it changed.

