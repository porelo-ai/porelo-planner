# PL25 — Findings In The Screen's Older Code

```json
{
  "$schema": "issue-1.json",
  "id": "PL25",
  "title": "Findings In The Screen's Older Code",
  "state": "Backlog",
  "size": "-",
  "priority": "Medium",
  "rank": 14,
  "blockedBy": [],
  "covers": "Moves staged for one repository of a project are dropped when the project is rebound to another; every agent's or repository's text the screen shows is stripped of control and bidirectional characters; a held-back board is not live, each watch tells its own reconnect, and a disposed board opens no watch"
}
```

**What must be true.** A move staged on the board is saved only to the repository it was staged against: when the
window rebinds the project to another repository, the staged moves are dropped and the person sees why. Every text the
screen shows that an agent or a repository wrote is shown without control characters and bidirectional overrides,
as refusal words already are. That covers the workflow's faults, a reader's findings, a task's raw answer, a proposal's
briefs and the names a Start answers. A board never stands as live on a board it has only held back: one that arrives during a
reload or a save waits as stale until it is accepted, and catching up ends with it. Each watch of a source tells its
own reconnect, so two boards on one source never hold each other in catching up. A board disposed or rebound inside
its own notification opens no watch afterwards.
