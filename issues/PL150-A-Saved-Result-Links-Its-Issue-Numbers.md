# PL150 — A Saved Result Links Its Issue Numbers

```json
{
  "$schema": "issue-1.json",
  "id": "PL150",
  "title": "A Saved Result Links Its Issue Numbers",
  "state": "Implementing",
  "size": "S",
  "priority": "High",
  "rank": 70,
  "blockedBy": [],
  "covers": "The Git store writes a stage result into an issue's file with every issue number of the repository as the link to its index row, so a saved analysis or estimate never makes the store's own check warn"
}
```

**Why this size.** S - the Git store's result text and the stage order's instructions, with their unit tests; no
contract.

**What must be true.** A person whose analysis or estimate the planner saved sees no new warning on the board for the
issue's file.

## Plan

- Seen in a live run: after a saved analysis, the board warned "porelo-pi: issues/PI68-...md:42: PI68 is not a link to
  its index row", and the same for line 44. `ResultText` (`planner/store/git`) writes the result's plan, what is still
  to be checked and the findings as the agent gave them, so an issue number the agent wrote bare stands bare in the
  file, which the store's own issue check then names.
- `ResultText` writes each issue number of the repository's index that stands bare in the result's text as the link to
  its row, `[<NUMBER>](README.md#<number>)`, leaving numbers already linked, inside code spans and fences, as they are.
- The stage order's instructions (`StageOrder`, `planner/core`) say to name this repository's issues by their number and
  no other repository's.
- Red first, in `ResultTextTest`: a plan and a finding naming the issue itself and another issue of the index bare, one
  already linked and one in a code span; the written file passes the store's issue check and links exactly the bare
  ones. Harm: a saved result that makes the store warn on every board.

## Acceptance

- The test above, seen red against today's text; the Git store's suite passes.

## To be checked

- What the store's issue check says of another repository's issue number in an issue's file, and what the result's text
  does with one.
