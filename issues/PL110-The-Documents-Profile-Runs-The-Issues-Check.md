# PL110 — The Documents Profile Runs The Issues Check

```json
{
  "$schema": "issue-1.json",
  "id": "PL110",
  "title": "The Documents Profile Runs The Issues Check",
  "state": "Review",
  "size": "S",
  "priority": "Low",
  "rank": 46,
  "blockedBy": [],
  "covers": "`-Pdocuments test` runs a test that checks this repository's own issues and index with the planner's issue check, and fails when it runs no test, so a change to issues alone is checked by the step that claims to check it"
}
```

**Size:** S - one test and one profile in `app`, no contract.

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
  - `RepositoryIssuesTest` in `planner/app`, tagged `documents`, finds the repository root two levels above
    `planner/app`, runs `WorkFixture.planner("issues", "check", issues.toString())` (`WorkCommands.run`, the same entry
    point as `PlannerApplication issues check issues`) over the repository's `issues/` directory, and fails with the
    check's own output lines when it refuses.
  - A second test in `RepositoryIssuesTest`, also tagged `documents`, copies `issues/` into a `@TempDir`, removes one
    index row starting with `| <a id="pl` from the copy's `README.md`, runs the same entry point over the copy, and
    asserts that it refuses.
  - A `documents` profile in `planner/app/pom.xml` sets `<failIfNoTests>true</failIfNoTests>`, so a `-Pdocuments test`
    run that executes no tagged test in `planner/app` fails while modules without tagged tests still pass.
  - `.github/workflows/shared-rules.yml`'s step *The documents* runs `./mvnw -B -U -s settings.xml -Pdocuments test`
    directly, so its exit code fails the workflow run.
- **Test, unit level, red first.** `RepositoryIssuesTest` runs the check over a copy of `issues/` with one index row
  removed and asserts the refusal. Harm: an index that disagrees with its issues pushed as checked.
- **Reading by another model.** The check's confirmation text is no longer held verbatim; the exit code and an empty
  standard error carry the harm. The repository root is found two levels above the module, Surefire's working
  directory, which no pom of this repository changes.

## Acceptance

- `-Pdocuments test` runs the new test and passes on the current issues; it fails on an index that disagrees with its
  issues, and fails when no test carries the tag.
