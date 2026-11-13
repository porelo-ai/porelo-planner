# PL110 — The Documents Profile Runs The Issues Check

```json
{
  "$schema": "issue-1.json",
  "id": "PL110",
  "title": "The Documents Profile Runs The Issues Check",
  "state": "Implementing",
  "size": "S",
  "priority": "Low",
  "rank": 46,
  "blockedBy": [],
  "covers": "`-Pdocuments test` runs a test that checks this repository's own issues and index with the planner's issue check, and fails when it runs no test, so a change to issues alone is checked by the step that claims to check it"
}
```

**Size:** S - one test in `app` and the profile in the root pom, no contract.

**Estimate:** 33 min, the measured median of S.

**What must be true.** A change to issues or documents alone is handed over with `./mvnw -B -s settings.xml -Pdocuments
test` green, and the Shared rules check runs the same profile. Today no test carries the tag `documents`: the profile
runs no test in any module and passes, so the step checks nothing. The repository's issues are checked only when
someone remembers to run `issues check issues` by hand.

## Plan

- **Today, read in the code.** The root `pom.xml`'s profile `documents` sets `groups` to `documents`. No test under
  `planner/` carries `@Tag("documents")`, and none reads the repository's own `issues/` or `doc/`: the tests that name
  such directories build their own fixtures in a temporary directory. A run of the profile reports `Tests run: 0` in
  every module.
- **The change.**
  - A test in `planner/app`, tagged `documents`, runs the planner's own issue check over the repository's `issues/`
    through the same entry point as `PlannerApplication issues check issues`, and fails with the check's own lines when
    it refuses.
  - The profile sets Surefire's `failIfNoSpecifiedTests` so that a run of the profile with no tagged test fails.
- **Test, unit level, red first.** The new test is seen red over a copy of `issues/` with one index row removed. Harm:
  an index that disagrees with its issues pushed as checked.

## Acceptance

- `-Pdocuments test` runs the new test and passes on the current issues; it fails on an index that disagrees with its
  issues, and fails when no test carries the tag.

## To be checked

- Whether the Shared rules check's step reads the profile's exit code alone, so the new failure reaches it.
