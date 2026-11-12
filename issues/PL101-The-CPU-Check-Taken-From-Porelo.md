# PL101 — The CPU Check Taken From Porelo

```json
{
  "$schema": "issue-1.json",
  "id": "PL101",
  "title": "The CPU Check Taken From Porelo",
  "state": "Implementing",
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
- **Test.** No new test: the native build's check of the executable is the test. It is seen red once, by asking it
  for a narrower instruction set than the executable has, before the hand-over.

## Acceptance

- The native build resolves `porelo-cpu-check` at porelo's version, checks the executable and passes.
- No module of the build reads the build tools' copy: `dependency:tree` shows none.
