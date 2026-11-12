# PL94 — Findings Read From The Reader's Committed File

```json
{
  "$schema": "issue-1.json",
  "id": "PL94",
  "title": "Findings Read From The Reader's Committed File",
  "state": "Accepted",
  "size": "M",
  "priority": "High",
  "rank": 2,
  "blockedBy": ["PL90"],
  "covers": "A reading's findings are read from the file the reader committed where its work landed, checked by today's rules for a Findings block, with the answer's block as the fallback while readers do not yet write the file"
}
```

**Size:** M - reading a file at a landed commit through the Git store, the check shared with today's answer, and the
judging in `core`.

**What must be true.** After a reader ends and its work has landed, the planner reads
`<directory>/<ISSUE>-reading-<round>.md` at the reader's landed commit, checks its `Findings:` block by the rules the
answer's block has today (numbered rows, blocking or note, file and line), and judges blocking findings as today. A
clean reading writes the file with a `Findings:` line and no row, so a missing file means the reader did not finish its
work, never a clean reading, and the issue waits for the person. While readers do not yet write the file, the answer's
`Findings:` block stays the fallback. The rework's order names the file, which the rework deletes with its fix.
