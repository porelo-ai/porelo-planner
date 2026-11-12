# PL101 — The CPU Check Taken From Porelo

```json
{
  "$schema": "issue-1.json",
  "id": "PL101",
  "title": "The CPU Check Taken From Porelo",
  "state": "Backlog",
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
- **The change.** The root `pom.xml` names porelo's version in a property of its own, and the check's dependency takes
  it. Nothing else changes. Built with `-U`, so the snapshot is read from Central.
- **Test.** No new test: the native build's check of the executable is the test. It is seen red once, by asking it
  for a narrower instruction set than the executable has, before the hand-over.

## Acceptance

- The native build resolves `porelo-cpu-check` at porelo's version, checks the executable and passes.
- No module of the build reads the build tools' copy: `dependency:tree` shows none.

## To be checked

- The version under which porelo publishes `porelo-cpu-check`. porelo's tree builds it at its own version
  (0.2.2-SNAPSHOT). Central's snapshot index of the artifact lists 0.5.0, 0.5.1 and 0.5.2-SNAPSHOT, which are the
  build tools' versions.
