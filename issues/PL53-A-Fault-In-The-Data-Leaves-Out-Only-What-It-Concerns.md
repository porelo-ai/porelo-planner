# PL53 — A Fault In The Data Leaves Out Only What It Concerns

```json
{
  "$schema": "issue-1.json",
  "id": "PL53",
  "title": "A Fault In The Data Leaves Out Only What It Concerns",
  "state": "Review",
  "size": "M",
  "priority": "Highest",
  "rank": 1,
  "blockedBy": [],
  "covers": "An issue file without a head, a story file that cannot be read, a story naming an issue no repository has, or a story whose state cannot be told is left out and answered among the board's faults; the rest of the board is read and shown, and a move that would rewrite an index the fault breaks is refused with the fault named"
}
```

**Size:** M - grew from S with the reading: the Git store's reading of files, indexes and the board, the work run's
stop, and the board's answer with the contract's description of its faults.

**What must be true.** A mistake in one file of the store - a story naming an issue that is not there, an issue
file without its head, a story file that cannot be read - never takes the whole board away: the board is shown,
and what was left out is named above it.

## Plan

- Today one such mistake throws while the board is put together, and the whole project reads as unreadable: a
  story naming another repository's issue before that issue was pushed showed no board at all.
- `StoreReader` leaves out an issue or story file it cannot read and names it in the part's faults; `GitStore`
  leaves out a story's reference that names no issue of the project, and a story whose state cannot be told, and
  names each; `Board` carries them as `faults`, apart from the work rules' faults, so `Moves` does not refuse a move
  for them; `BoardController` answers them in the board's and workflow's `faults`, which the screen already shows above
  the board.
- Red first in `store/git`: `GitStoreReadTest` - a headless issue file, an unreadable story file, and a story naming
  an issue under another repository each leave the rest of the board read, with the fault named.

## Acceptance

- Each of the three mistakes leaves a board that is read, the faulty item left out and named; the tests fail on
  the store that refused the whole board.

## Reading

Read by another model against `spring-boot-patterns`, `graal` and `api-contract-review`; each finding checked in the
code:

- A save rewrites the indexes with `porelo-stories`, which reports a broken file as a fault, and `refuseFaults`
  refuses the save: while one file is broken, every move in its repository is refused, naming the fault. Kept on
  purpose: a save past the fault would leave an index that is not what `porelo-release stories index` writes, or drop
  the broken item's row. The issue's words now say so.
- An issue whose file is there but cannot be read was shown from its index row with no text. Fixed: it is left out;
  `anIssueWhoseHeadBrokeIsLeftOutAndNotShownFromItsIndexRow`, seen red first.
- Two files of one story or issue id took the whole board away, or after the change could make a save write the
  copy left out. Fixed: both copies are left out and named, and neither keeps a path a save could write;
  `twoFilesOfOneIdAreAFaultAndBothAreLeftOut`, seen red first. A save names only stories `Moves` found on the
  board, so a story left out for its state is never written.
- An issue in a state its own repository's workflow knows but the board's does not, and an index that cannot be
  read, took the whole board away. Fixed: the issue is left out and named, the index is named and its rows left
  out; `anIssueInAStateTheBoardDoesNotKnowIsAFault` and `anIndexThatCannotBeReadIsAFaultAndTheIssueFilesAreStillRead`,
  seen red first.
- A story left off the board while its task ran could not be stopped. Fixed: a stop with a run needs no story on the
  board; `WorkRunTest.aRunningStoryLeftOffTheBoardCanStillBeStopped`, seen red first.
- A left-out sub-issue or reference changes what the board shows of its parent or story; a move over it is refused by
  the index fault above, so nothing is written from that view.
- A broken issue head was named without its file, a story with only missing issues read as naming none, and
  `Answers` still described the faults as the rules' alone. Fixed in words, without tests.
