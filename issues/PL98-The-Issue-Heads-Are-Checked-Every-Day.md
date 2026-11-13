# PL98 — The Issue Heads Are Checked Every Day

```json
{
  "$schema": "issue-1.json",
  "id": "PL98",
  "title": "The Issue Heads Are Checked Every Day",
  "state": "Review",
  "size": "XS",
  "priority": "Low",
  "rank": 4,
  "blockedBy": [],
  "covers": "The daily Shared rules check runs `issues check issues`, so an issue head broken by a push that started no Build is found within a day"
}
```

**Size:** XS - covered by the `documents` profile's issue check test, no contract.

**Estimate:** 9 min, the planned median of XS.

**What must be true.** Once a day, the scheduled *Shared rules check* checks this repository's issue heads and index
with `issues check issues`. An issues-only push carries `[skip ci]` and starts no Build, so a broken head or an index
that no longer matches its issues is found the next day, not at the next code push.

## Plan

- `.github/workflows/shared-rules.yml`'s step *The documents* runs `./mvnw -B -U -s settings.xml -Pdocuments test` on
  the daily schedule, on a pull request and by hand.
- `RepositoryIssuesTest` in `planner/app`, tagged `documents`, is the check: it runs the planner's `issues check` over
  this repository's `issues/` directory during that step, with no download and no workflow step of its own, and fails
  with the check's fault lines when the check refuses.
- **Test, unit level, red first.** `RepositoryIssuesTest` copies `issues/` into a temporary directory, removes an index
  row from the copy's `README.md`, and asserts that the check refuses it.

## Acceptance

- The scheduled run and a run started by hand both run the check through `-Pdocuments test`. A head or index broken on
  purpose turns the run red, and its log names the file and the fault.
