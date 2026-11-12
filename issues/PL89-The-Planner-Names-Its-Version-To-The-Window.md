# PL89 — The Planner Names Its Version To The Window

```json
{
  "$schema": "issue-1.json",
  "id": "PL89",
  "title": "The Planner Names Its Version To The Window",
  "state": "Backlog",
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
  - `planner/app/pom.xml` writes the version and the commit into a resource, `planner-build.properties`: the commit
    from the checkout's `HEAD` at build time, empty when there is no checkout.
  - The resource is in the native image's resources.
  - `HealthController` answers a record, `Health(status, version, commit)`, not a `Map`. `Map.of` refuses a null
    value, so it could not answer `"commit": null`. The record is added to the records `ApiMatchesDescriptionTest`
    holds to the description. A resource that is missing or malformed fails the start loud; the planner never
    answers a made-up version.
- **What changes with it.** The Windows install check's ready script compares the answer verbatim today. It reads
  the JSON and checks `status` instead, in the change that ships it.
- **Tests, by level.**
  - Unit, app: the build file's values are answered as they stand. A malformed version or commit in the file fails
    the start. Harm: a window showing a planner version that is not the one running.
  - Integration, app: an HTTP call to `/health` answers a known commit as 40 lowercase hex, and a build file without
    a commit as an explicit JSON `null`, with every required field present. `ApiMatchesDescriptionTest` holds the
    `Health` record to the description. Harm: a checkout-free build answering 500, or leaving `commit` out.
  - Native: the built executable's `/health` names the version and the commit of its build; the native start test
    reads it.
- **The plan reading's points**, all taken: one version in every build; a record instead of a map; HTTP checks for
  both a hash and `null`.

## Acceptance

- `/health` names the package version and the commit of the running executable, on the JVM and native. A build
  without a checkout answers `commit: null`. Each new test was seen red.
- The suite, the native build and the documents' checks pass.
