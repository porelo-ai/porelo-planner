# PL124 — A Reader's Leftovers Do Not Stop Its Reading

```json
{
  "$schema": "issue-1.json",
  "id": "PL124",
  "title": "A Reader's Leftovers Do Not Stop Its Reading",
  "state": "Review",
  "size": "XS",
  "priority": "High",
  "rank": 1,
  "blockedBy": [],
  "covers": "A reader that ends finished with commits or staged files left in its workspace is judged by its findings, not raised as UNPUSHED, since a reader delivers nothing but its answer"
}
```

**Size:** XS - one branch in `WorkEnds.judge` with its unit tests, no contract.

**What must be true.** A reading that finished is judged by its findings block, whatever the reader left in its own
workspace: a reader that checked the landed work out to read it, or committed nothing it should have, does not raise
its issue as UNPUSHED and does not hold it in Reading. A builder that leaves work unpushed still needs the person.

## Plan

- **Today, read in the code.** `WorkRun` judges every task's end through `WorkEnds.Outcome.of(finished, unpushed,
  declared)`, which answers UNPUSHED for any role with unpushed above zero; `WorkEnds.judge` turns UNPUSHED into
  `NeedsOperator(UNPUSHED)` before it reads a reader's findings. A story test's reader that checked the builder's landed
  commit out into its workspace ended with unpushed 4 and no landing, and its issue stayed in Reading with UNPUSHED.
- **The change.** `WorkEnds.judge` reads a READER's findings when it finished, whatever its unpushed count; FAILED and
  BLOCKED stay as they are. The screen's `planningEndRaised` raises UNPUSHED for no reader and INVALID_FINDINGS for a
  reader whatever it left unpushed, and `planner.yaml` says how a reader's end is judged. `WorkRun.unpushedLeft`
  refuses a Start's pass-over for a builder's unpushed commits only, not for what a reader left.
- **Test, unit, red first.** `WorkEndsTest`: a reader that finished with unpushed 4 gives its clean or blocking
  findings, and a builder with unpushed 4 still needs the person. The two older tests that held a finished reader's
  unpushed end as needing the person now hold only an unfinished reader and an unpushed builder. Harm: a clean
  reading held in Reading, or a builder's unpushed work passed over.
- **Test, unit, red first.** `WorkRunTest`: a Start passing over the stop of a reader that left files unpushed is not
  refused for them. Harm: a reader's leftovers blocking the person's Start.
- **Reading by another model.** It found the pass-over refusal above, now fixed. Declined notes: a reader's own
  commits are dropped unseen, which is the rule, since every rework and build runs as BUILDER and a reader delivers
  only its answer; a declared BLOCKED or FAILED reader still needs the person whatever it left unpushed, as
  `Outcome.of` decides before UNPUSHED.
