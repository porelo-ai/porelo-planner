# PL53 — A Fault In The Data Leaves Out Only What It Concerns

```json
{
  "$schema": "issue-1.json",
  "id": "PL53",
  "title": "A Fault In The Data Leaves Out Only What It Concerns",
  "state": "Implementing",
  "size": "S",
  "priority": "Highest",
  "rank": 1,
  "blockedBy": [],
  "covers": "An issue file without a head, a story file that cannot be read, a story naming an issue no repository has, or a story whose state cannot be told is left out and answered among the board's faults; the rest of the board is read and shown, and moves stay possible"
}
```

**Size:** S - the Git store's reading and the board's answer, with the contract's description of its faults.

**What must be true.** A mistake in one file of the store - a story naming an issue that is not there, an issue
file without its head, a story file that cannot be read - never takes the whole board away: the board is shown,
and what was left out is named above it.

## Plan

- Today one such mistake throws while the board is put together, and the whole project reads as unreadable: a
  story naming another repository's issue before that issue was pushed showed no board at all.
- `StoreReader` leaves out an issue or story file it cannot read and names it in the part's faults; `GitStore`
  leaves out a story's reference that names no issue of the project, and a story whose state cannot be told, and
  names each; `Board` carries them as `faults`, apart from the work rules' faults, so a move is never refused for
  them; `BoardController` answers them in the board's and workflow's `faults`, which the screen already shows above
  the board.
- Red first in `store/git`: `GitStoreReadTest` - a headless issue file, an unreadable story file, and a story naming
  an issue under another repository each leave the rest of the board read, with the fault named.

## Acceptance

- Each of the three mistakes leaves a board that is read, the faulty item left out and named; the tests fail on
  the store that refused the whole board.
