# PL124 — A Reader's Leftovers Do Not Stop Its Reading

```json
{
  "$schema": "issue-1.json",
  "id": "PL124",
  "title": "A Reader's Leftovers Do Not Stop Its Reading",
  "state": "Idea",
  "size": "-",
  "priority": "Medium",
  "rank": 1,
  "blockedBy": [],
  "covers": "A reader that ends finished with commits or staged files left in its workspace is judged by its findings, not raised as UNPUSHED, since a reader delivers nothing but its answer"
}
```

**What must be true.** A reading that finished is judged by its findings block, whatever the reader left in its own
workspace: a reader that checked the landed work out to read it, or committed nothing it should have, does not raise
its issue as UNPUSHED and does not hold it in Reading. A builder that leaves work unpushed still needs the person.

## Plan

- **Today, read in the code.** `WorkRun` judges every task's end through `WorkEnds.Outcome.of(finished, unpushed,
  declared)`, which answers UNPUSHED for any role with unpushed above zero; `WorkEnds.judge` turns UNPUSHED into
  `NeedsOperator(UNPUSHED)` before it reads a reader's findings. A story test's reader that checked the builder's landed
  commit out into its workspace ended with unpushed 4 and no landing, and its issue stayed in Reading with UNPUSHED.
- **The change.** `WorkEnds.judge` reads a READER's findings when it finished, whatever its unpushed count; FAILED and
  BLOCKED stay as they are. The reader's order may also ask it to read the work with `git show` and `git diff` rather
  than check it out.
- **Test, unit, red first.** `WorkEndsTest` and `WorkRunTest`: a reader that finished with unpushed 4 and a clean
  findings block moves its issue on, one with blocking findings stops its reading with them; a builder with unpushed 4
  still needs the person. Harm: a clean reading held in Reading, or a builder's unpushed work passed over.
