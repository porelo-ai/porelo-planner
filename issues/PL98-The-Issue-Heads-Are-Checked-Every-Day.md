# PL98 — The Issue Heads Are Checked Every Day

```json
{
  "$schema": "issue-1.json",
  "id": "PL98",
  "title": "The Issue Heads Are Checked Every Day",
  "state": "Backlog",
  "size": "XS",
  "priority": "Low",
  "rank": 4,
  "blockedBy": [],
  "covers": "The daily Shared rules check runs `issues check issues`, so an issue head broken by a push that started no Build is found within a day"
}
```

**Size:** XS - one step in one workflow, no contract.

**Estimate:** 9 min, the planned median of XS.

**What must be true.** Once a day, the scheduled *Shared rules check* checks this repository's issue heads and index
with `issues check issues`. An issues-only push carries `[skip ci]` and starts no Build, so a broken head or an index
that no longer matches its issues is found the next day, not at the next code push.

## Plan

- `.github/workflows/shared-rules.yml` gains a step `issues check issues`, run by the released planner executable,
  pinned by version and sha256 as a download from Artifactory. It exits non-zero on a fault, so the run goes red.
- A level of test is not needed: the step is the check. It is seen red once, on a branch with a broken head, before
  the hand-over.

## Acceptance

- The scheduled run and a run started by hand both run the step. A head broken on purpose turns the run red, and its
  log names the file and the fault.
