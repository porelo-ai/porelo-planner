# PL122 — A Rework That Ends Offers Its Reading

```json
{
  "$schema": "issue-1.json",
  "id": "PL122",
  "title": "A Rework That Ends Offers Its Reading",
  "state": "Implementing",
  "size": "S",
  "priority": "High",
  "rank": 58,
  "blockedBy": [],
  "covers": "A rework task that ended finished and landed moves its issue on to its reading as the rule's then says, and a next step the planner cannot take is said in words, in the log and on the row, instead of a bare NEXT_STEP_UNAVAILABLE"
}
```

**Size:** S - the end of a rework in `NextStep` and `WorkRun`, the words of an unavailable next step, and their unit
tests in `core`.

**Estimate:** 120 min, a guess: no median of S is measured.

**What must be true.** After a rework task ends finished with its work landed, the issue goes on to its reading as the
rule says, and a reader is offered. When the planner cannot take a next step, the person and the log read which step
and why. On the VM a rework under the rule Reworking (from Reading, done Review, then Reading, per repository) ended
finished, landed and with nothing unpushed, and its issue stayed in Reworking under NEXT_STEP_UNAVAILABLE with no words
and no log line. The rework agent had also moved the issue's own files on its branch.

## Plan

- To be read in the code: which of the places in `NextStep` that raise NEXT_STEP_UNAVAILABLE took this end, reproduced
  in `WorkRunTest` through the same entry point, red first.
- Every place that raises NEXT_STEP_UNAVAILABLE keeps one line of why, logged and shown with the reason.
- An agent's order says that the planner alone moves the issue and writes its files, `issues/` and `stats/`.

## Acceptance

- The unit test that reproduces the VM's end, seen red; on the VM the rework's end offers a reader and the issue
  stands in Reading.
