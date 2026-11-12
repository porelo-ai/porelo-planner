# PL101 — The CPU Check Taken From Porelo

```json
{
  "$schema": "issue-1.json",
  "id": "PL101",
  "title": "The CPU Check Taken From Porelo",
  "state": "Review",
  "size": "XS",
  "priority": "High",
  "rank": 26,
  "blockedBy": [],
  "covers": "The build checks the native executable's instruction set with porelo's porelo-cpu-check at porelo's published version, not with the build tools' copy, which the build tools drop"
}
```

**Size:** XS - one dependency's version in `planner/app/pom.xml` and a property in the root, no contract.

**Estimate:** 9 min, the planned median of XS.

**What must be true.** The build keeps checking that the native executable needs exactly x86-64 and nothing more, with
the check porelo publishes. The build tools stop publishing their copy, and the planner's build must not depend on it.

## Plan

- **Today, read in the code.** `planner/app/pom.xml` takes `org.fuin.porelo:porelo-cpu-check` at
  `${porelo.buildtools.version}`, in the native profile's check of the executable.
- **The change.** The root `pom.xml` names porelo's version of the check, `0.5.1-SNAPSHOT`, in a property of its own,
  and the check's dependency takes it. The build tools publish the same artifact at their own, higher versions until
  they drop it, so the version is named, never taken from the build tools'. Nothing else changes. Built with `-U`, so the snapshot is read from Central.
- **The change, as built.** The root names `porelo.checks.version`, and the check in `planner/app/pom.xml` takes it.
- **Test.** No new test: the native build's check of the executable is the test. It was seen red on a copy of the
  executable whose required set names AVX2 in place of SSE2: porelo's check refused it, naming AVX2 beyond x86-64 v1.

## Acceptance

- The native build resolves `porelo-cpu-check` at porelo's version, checks the executable and passes.
- No module of the build reads the build tools' copy: `dependency:tree` shows none.
