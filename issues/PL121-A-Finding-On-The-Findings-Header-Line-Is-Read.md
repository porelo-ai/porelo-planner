# PL121 — A Finding On The Findings Header Line Is Read

```json
{
  "$schema": "issue-1.json",
  "id": "PL121",
  "title": "A Finding On The Findings Header Line Is Read",
  "state": "Review",
  "size": "XS",
  "priority": "High",
  "rank": 57,
  "blockedBy": [],
  "covers": "A reader that writes its first finding on the Findings header line has its findings read, and the reader's order shows the header on a line of its own with each row below it"
}
```

**Size:** XS - one branch in `WorkEnds.findings`, the order's words in `NextStep`, and one unit test.

**Estimate:** 17 min, the measured median of XS.

**What must be true.** A reader that writes `Findings: 1. blocking | a.java | 26 | ...` has that finding read, not its
reading stopped as unreadable; and the reader's order shows the shape it asks for, so fewer readers vary it. On the VM a
reader with one blocking finding put it on the header line, and the reading stopped instead of going to Rework.

## Plan

- **Today, read in the code.** `WorkEnds.findings` takes text after `Findings:` on the header line only as a word for
  none; anything else there refuses the whole block as INVALID_FINDINGS. `NextStep`'s order to a reader says to end
  with one `Findings:` block of consecutive numbered lines, without saying that the header stands alone.
- **The change.** Text after the header that is not a word for none is read as the block's first row, under the same
  rules as any row; a word for none beside a row stays refused. The order says that `Findings:` stands on a line of its
  own, the rows below it with no blank line between them, and names the line column's range form.
- **Test, red first.** Unit, `WorkEndsTest`: a block whose first row stands on the header line is read with all its
  rows; text there that is no row is still refused. Harm: a reading's blocking findings thrown away whole.

## Acceptance

- The unit test, seen red against today's parser; the core suite green; on the VM the reading with its row on the
  header line reaches Rework by its blocking finding.
