# PL120 — A Blank Line Under The Findings Header Is Read

```json
{
  "$schema": "issue-1.json",
  "id": "PL120",
  "title": "A Blank Line Under The Findings Header Is Read",
  "state": "Implementing",
  "size": "XS",
  "priority": "High",
  "rank": 56,
  "blockedBy": [],
  "covers": "A reader's Findings block with blank lines between its header and its first row, as Markdown writes a list under a heading, is read with its rows instead of stopping the reading as unreadable"
}
```

**Size:** XS - one loop in `WorkEnds.findings` and one unit test.

**Estimate:** 17 min, the measured median of XS.

**What must be true.** A reader that writes its block as Markdown does, `Findings:`, a blank line, then its rows, has
its findings read. On the VM a reader with a blocking finding answered in that shape, and the reading stopped as
unreadable instead of going to Rework.

## Plan

- **Today, read in the code.** `WorkEnds.findings` drops blank lines only after the last row; a blank line right under
  the header is a row that matches no `N. ` row, so the whole block is refused as INVALID_FINDINGS.
- **The change.** Blank lines between the header and the first row are dropped as those after the last row are; a
  blank line between two rows stays refused.
- **Test, red first.** Unit, `WorkEndsTest`: a block with blank lines under its header is read with both rows, and a
  `(none)` under a blank line is a clean reading. Harm: a reading's blocking findings thrown away whole.

## Acceptance

- The unit test, seen red against today's parser; the core suite green; on the VM the reading with a blank line under
  its header reaches Rework by its blocking finding.
