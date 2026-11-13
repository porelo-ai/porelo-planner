# PL118 — A Reading Takes A Line Range And An Unreadable One Is Read Again

```json
{
  "$schema": "issue-1.json",
  "id": "PL118",
  "title": "A Reading Takes A Line Range And An Unreadable One Is Read Again",
  "state": "Review",
  "size": "M",
  "priority": "High",
  "rank": 54,
  "blockedBy": [],
  "covers": "A Findings row naming a line range is read with its first line as the line and the range kept in its text, and a reading whose findings could not be read stops under Needs you with Read again and Review anyway, Rework staying for blocking findings"
}
```

**Size:** M - the row parser in `core`, a reading that could not be read stopping like one with blocking findings in
`WorkRun` and `ReadingCycle`, the contract's words for it, and the screen's choices on that stop.

**Estimate:** 67 min, the measured median of M.

**What must be true.** A reader that names a range of lines, `34-40`, has its findings read, not thrown away whole. A
reading whose findings could not be read leaves the person a way on: read the same build again, or take it to Review
anyway; Rework, which needs findings to work from, stays for a reading with blocking findings.

## Plan

- **Today, read in the code.** `WorkEnds.finding` takes the line column only as one number or `-`, so a row with
  `34-40` makes the whole block invalid, and `WorkEnds.judge` answers `NeedsOperator(INVALID_FINDINGS)`. That end
  raises the issue under Needs you without stopping its reading (`rounds.stopped()` stays false), so `chooseReading`
  refuses every choice as `ReadingNotStopped`, and the screen offers none.
- **A range.** The line column takes `<a>-<b>` with `1 <= a <= b`, both within an int: the finding's line is `a`, and
  its text starts with `lines a-b: `, so the range still shows. Anything else in the column is refused as today.
- **Read again.** A reader that ended with findings that could not be read stops its reading: `NextStep.after`, where
  `WorkRun.report` judges a reader's end, marks the issue's rounds stopped without counting a round, its reason stays
  `INVALID_FINDINGS`, and it keeps no findings.
  `ReadingCycle.choose` then takes `ONE_MORE_ROUND` (a new reading of the same build, shown as Read again) and
  `REVIEW_ANYWAY`; it refuses `REWORK` for such a stop as `ReworkNeedsFindings`, since there is nothing to rework from,
  a conflict (409) like the other reading refusals.
  `planner.yaml` says so under `chooseReading` and the attention reason; the screen shows Read again and Review
  anyway on that entry, and no Rework.
- **Tests, red first.**
  - Unit, `WorkEndsTest`: a row with `34-40` is read with line 34 and its range in the text; `40-34`, `0-3`, `3-`
    and `a-b` are refused. Harm: a reading's blocking findings thrown away, or a malformed block taken in.
  - Unit, `WorkRunTest` over a fake store: a reader ending with an unreadable block stops its reading with no round
    counted; `ONE_MORE_ROUND` offers a reader of the same build, `REVIEW_ANYWAY` saves the done state, `REWORK` is
    refused. Harm: a person left with no way on, or a rework started with nothing to work from.
  - Widget, screen: an issue stopped for unreadable findings shows Read again and Review anyway, and no Rework.

## Acceptance

- The tests above, each seen red first; the full build and the screen's suite green; on the VM, a reading with a line
  range reaches Review or Reworking by its findings, and an unreadable one offers Read again.
