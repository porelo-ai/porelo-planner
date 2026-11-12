# PL99 — A Dropped Move Says Why

```json
{
  "$schema": "issue-1.json",
  "id": "PL99",
  "title": "A Dropped Move Says Why",
  "state": "Review",
  "size": "S",
  "priority": "Highest",
  "rank": 6,
  "blockedBy": [],
  "covers": "A move a person chose on a story's page that the screen can no longer stage - its issue changed state or a save began while the menu stood open - is not dropped in silence: the page says why and the person chooses again"
}
```

**Size:** S - one class of the screen package and its widget tests, no contract.

**Estimate:** 33 min, the measured median of S.

**What must be true.** When a person chooses a new state for an issue on a story's page and the screen cannot stage
that move, the page says why, beside the issue, and nothing is staged. Today the choice is dropped without a word when
the issue list was read again while the menu stood open and the chosen state is no longer one the issue can move to,
or when a save began in the meantime. The person sees no change and no reason. While tasks move issues in the
background, this happens in normal use.

## Plan

- **Today, read in the code.** The story page's menu and the board's actions call `PlanningBoard.moveIssue`
  (`screen/lib/src/planning_board.dart`). `moveIssue` returns without a word while a save runs or the board is not
  live and complete, and when the destination is not one the issue may enter. It also stages from the menu's old state
  when a task has moved the issue since. `PlanningDetail.queueMove` has the same silent returns.
- **The change, as built.** `moveIssue` refuses each case with words in `moveProblem`, which the board and the story
  page already show. Nothing is staged on a refusal.
  - The move is judged on the board's own issue: its state and whether it may enter the destination.
  - Each menu item carries the state the menu showed when it opened. When the board holds the issue in another state
    now, the move is refused, naming the state it stands in.
  - An issue missing from a board that reports every story's issues is refused as gone. Where the board leaves a
    story's issues out, the page's copy is used, as before.
  - *Undo this move* always takes the draft back.
  - `queueMove` refuses an issue its page does not list, and hands its own reason to the board.
- **Tests, red first, unit level** (`screen/test/planning_moves_test.dart`, `planning_detail_test.dart`). Each new
  test was seen red under a deliberate break of its rule:
  - a menu built before the issue moved on;
  - a menu that showed another state than the board holds;
  - a move chosen while a save runs;
  - a destination the issue may not enter;
  - entering a working state, judged on the board's issue and not the page's copy;
  - an issue no longer on the board;
  - undo after the board reports the issue elsewhere;
  - a story page asked to move an issue it does not list.

  Harm: a person's move lost without a word, staged from a state the issue has left, or staged for an issue the person
  did not see.
- **The code reading's findings.**
  - Fixed: the menu's state is taken when it opens; a move is judged on the board's issue; an issue gone from the
    board is refused; undo always works; the page refuses an issue it does not list.
  - Declined, one part of a finding: a move is refused when the page showed another state than the board holds. The
    refusal names the board's state, and the page is read again on every board change, so the person chooses again
    from what is true.

## Acceptance

- Both dropped paths show their reason beside the issue and stage nothing; a valid choice stages as today; each new
  test seen red; the screen's tests and the Maven screen step pass.
