# PL137 — A Move Planning Already Shows Is No Problem

```json
{
  "$schema": "issue-1.json",
  "id": "PL137",
  "title": "A Move Planning Already Shows Is No Problem",
  "state": "Implementing",
  "size": "XS",
  "priority": "Low",
  "rank": 60,
  "blockedBy": [],
  "covers": "When a move's save meets a changed planning and the planning read again already shows the story or issue in the move's target state, the board counts the move as done instead of refusing it in red; a state that differs from both the move's source and target is still refused"
}
```

**Size:** XS - one branch of one method in the screen package; no contract.

**What must be true.** A person who moves a story or an issue to a state that a push from elsewhere has just set sees
no problem: the move counts as done, at most with a quiet notice that planning already shows it. A state that differs
from both where the move came from and where it went is still shown in red as not moved.

## Plan

- In `screen/lib/src/planning_board.dart`, `_moveNow`'s handling of a `StaleRevision` or `PreviewChanged` refusal reads
  the board again and compares the newly reported state `now` with `shown.from` only. When `now == shown.to`, it removes
  the pending move, sets `moveProblem` to null and `moveNotice` to a quiet notice that planning already shows the move
  (for instance `'Planning already shows <id> in <state>.'`), and returns without previewing anew.
- When `now` is neither `shown.from` nor `shown.to`, the red "Planning changed: ... it was not moved." stays as it is;
  when `now == shown.from`, the move is previewed and asked about anew, as today.
- No test: the board only reads wrong here and nothing is lost or written twice, since no save is made in either case.

## Acceptance

- The screen's suite passes, and a move against a planning that already shows its target state shows no red problem.
