# PL89 — The Planner Names Its Version To The Window

```json
{
  "$schema": "issue-1.json",
  "id": "PL89",
  "title": "The Planner Names Its Version To The Window",
  "state": "Review",
  "size": "M",
  "priority": "High",
  "rank": 29,
  "blockedBy": [],
  "covers": "The planner answers its own version and the commit it was built from to the window, so the window's About names which planner it talks to, and a planner left running from an older install is seen at once"
}
```

**Size:** M - one contract change the window reads, and the build passing its version and commit into the
executable.

**Estimate:** 67 min, the measured median of M.

**What must be true.** The window can read which planner it talks to - its version, such as `0.1.0~snapshot.36`, and
the commit it was built from - and show it in its About, so a person sees at once when a planner from an older
install still runs beside a newer window; today `/health` answers its status alone and the address file holds port,
token and log.


## Plan

- **The place: `/health`, not the address file.** The live process answers `/health`, so the answer names the
  planner the window really talks to. `planner.json` would be a second copy, written once at the start, so it is not
  given the version.
- **The contract.** `GET /health` answers, with the token as today:

      {"status": "UP", "version": "0.1.0~snapshot.36", "commit": "<40 lowercase hex>"}

  - `version`: required, the version the package carries. That is `x.y.z` for a release and `x.y.z~snapshot.<run>`
    for a snapshot, the same string as the `.deb` and the `.rpm`, so the window can set it beside what the person
    installed.
  - `commit`: required, the 40-character commit the executable was built from, or `null` when the build had no Git
    checkout to read it from.
  - `status` stays `UP`. `Health` gains both fields as required in the description. Today's readers that parse the
    JSON and take `status` read on unchanged.
- **The build.**
  - The package version is computed in every build, not only under `dist`, where `pom.xml` computes
    `planner.package.version` today. A JVM build, the native build, the packages, the published executable's path and
    `/health` all take that one value.
  - `planner/app/pom.xml` computes `planner.package.version` with `build-helper-maven-plugin` in the main build,
    and filters that one resource, `planner-build.properties`, with the version and `planner.commit`.
  - The commit: the profile `checkout-commit`, active when the repository's `.git` exists, runs
    `git log -1 --format=planner.commit=%H` with `exec-maven-plugin` at `initialize` and reads the line into the
    project with `properties-maven-plugin` 1.3.0; a git that fails there fails the build. The profile
    `no-checkout`, active when `.git` is missing, sets `planner.commit` empty. The empty value stands in that
    profile alone, since a property the pom sets is fixed when the pom is read, and the native test would expect it.
  - `PlannerBuild(version, commit)` reads the resource and refuses a version not `x.y.z` or `x.y.z~snapshot.N`, a
    commit neither empty nor 40 lowercase hex, a missing key or a missing file, so its bean fails the start loud;
    the planner never answers a made-up version. `HealthController` registers the resource for the native image
    with a `RuntimeHintsRegistrar`, as the mock board's is.
  - `HealthController` answers a record, `Health(status, version, commit)`, not a `Map`. `Map.of` refuses a null
    value, so it could not answer `"commit": null`. The record is added to the records `ApiMatchesDescriptionTest`
    holds to the description.
- **What changes with it.** The Windows install check's ready script, which compares the answer verbatim, is not in
  this change; it changes with the change that ships it.
- **Tests, by level.**
  - Unit, app (`PlannerBuildTest`): the build file's values are answered as they stand, an empty commit as none,
    and a malformed version or commit, a missing key or a missing file fails the start. Harm: a window showing a
    planner version that is not the one running.
  - Integration, app (`HealthAnswerTest`): an HTTP call to `/health` answers a known commit as 40 lowercase hex,
    and a build without a commit as an explicit JSON `null`, with every required field present.
    `ApiMatchesDescriptionTest` holds the `Health` record to the description. Harm: a checkout-free build
    answering 500, or leaving `commit` out.
  - Native: the built executable's `/health` names the version and the commit of its build; `NativeStartIT` reads
    it against the values the build computed, handed in as system properties.
- **A changed tree.** `git log -1` names HEAD, so a local build from a tree with uncommitted changes names its
  HEAD; CI builds from a clean checkout.
- **The code reading's points**, all taken: `BuildResourceTest` holds the resource's commit to `git rev-parse HEAD`
  of the checkout, or to none without one, so the source of the commit is guarded apart from the native test, which
  takes the same property that filtered the resource; `ApiMatchesDescriptionTest` holds `Health`'s two patterns to
  the forms `PlannerBuild` checks; the changed tree is the point above.
- **The plan reading's points**, all taken: one version in every build; a record instead of a map; HTTP checks for
  both a hash and `null`.

## Acceptance

- `/health` names the package version and the commit of the running executable, on the JVM and native. A build
  without a checkout answers `commit: null`. Each new test was seen red.
- The suite, the native build and the documents' checks pass.
