# PL50 — Needs You Only For What A Person Must Do

```json
{
  "$schema": "issue-1.json",
  "id": "PL50",
  "title": "Needs You Only For What A Person Must Do",
  "state": "Idea",
  "size": "M",
  "priority": "High",
  "rank": 25,
  "blockedBy": [],
  "covers": "The planner marks an issue as needing the person only where they must act, and says what to do; an issue in a working state with no run of this planner is shown as such, not raised; a person can put a raised issue away until it changes"
}
```

**Size:** M - the board's answer and the work record in `app`, the contract's `Issue`, and the screen's row.

**What must be true.** Every row the planner raises in *Needs you* asks the person for something they can do, and says
what; what is only information is shown on the board, not raised; and a person can put a row away.

## Plan

- Today `WorkAnswers.lost` sets `needsYou` for every issue in a working state whose story has no run in this
  planner's own work record. Work started by hand, by an agent outside the planner or on another machine has no such
  run, so every issue in Implementing on a project worked that way is raised - with no reading counts, shown as
  "reading ? of ?". The person can do nothing with it.
- `needsYou` is raised only where the planner's own run waits on the person, and the answer names the action: a
  reading stopped at its limit (choose rework, Review or stop), a task that ended failed or unanswered (look at it,
  start again or stop), a run whose record became unreadable (start again or leave it). Each carries its reason as a
  word the screen shows with the action.
- An issue in a working state with no run here is not raised; the board answers it as `noRunHere`, and the screen
  shows it on the issue's row. A Manual issue is never raised for having no run.
- A raised issue can be put away: the person's dismissal is kept in the local work record with the issue's state and
  reason, and it is raised again only when either changes.
- Red first in `app`: a board with an issue in Implementing and no run here answers it without `needsYou`; one whose
  reading stopped at its limit answers `needsYou` with its reason.

## Acceptance

- *Needs you* holds only issues with a reason and an action; dismissing one keeps it away until its state or reason
  changes; an issue with no run here shows on the board and is not raised.
