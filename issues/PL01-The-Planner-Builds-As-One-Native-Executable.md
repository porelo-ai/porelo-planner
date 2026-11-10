# PL01 — The Planner Builds As One Native Executable

```json
{
  "$schema": "issue-1.json",
  "id": "PL01",
  "title": "The Planner Builds As One Native Executable",
  "state": "Review",
  "size": "M",
  "priority": "Medium",
  "rank": 1,
  "blockedBy": [],
  "covers": "One GraalVM native executable for any x86-64 CPU from four modules (`core`, `store/api`, `store/git`, `app`), started and answering in a native test, built by the Build workflow"
}
```

**Why this size.** four modules and their build, a native image with its instruction-set check, the Build workflow and
its first green run; no function yet.

**What must be true.** `./mvnw -B -s settings.xml -Pnative verify` builds porelo-planner into one GraalVM native
executable for any x86-64 CPU, which starts, answers a health call over HTTP and stops; the Build workflow does the
same on every push, and the same tests run on the JVM without `-Pnative`.

## Plan

- **Modules:** `planner/core` (plain Java, no Spring), `planner/store` grouping `planner/store/api` and
  `planner/store/git`, `planner/app` (Spring Boot, `spring-boot-starter-web`), `planner/test-support` (test scope
  only, never shipped). Each with a `README.md`, `@NullMarked` packages and NullAway as an error, and a test that
  fails on an unmarked package.
- **Spring Boot** from `spring-boot-dependencies` imported in the root (done in the repository's setup); in `app`
  `spring-boot-maven-plugin`'s `process-aot` and, under `-Pnative`, `native-maven-plugin` at porelo-parent's version
  with `-march=x86-64`. No `spring-boot-starter-parent`.
- **The instruction set** is checked on the built executable the way the other Porelo repositories check theirs (the
  shared `cpu-check` of porelo-buildtools), never only by the build argument.
- **A native test** (`NativeStartIT`, failsafe, only under `-Pnative`) starts the executable on a free port, reads
  `GET /actuator/health` - or a planner-own `/health` if Actuator costs too much in the image - and stops it, failing
  fast with the process output when it does not come up in ten seconds.
- **`.github/workflows/build.yml`** after porelo-adapters': pinned JDK, `-Pnative verify`, the pushed commits'
  messages; no publishing yet (packages are their own issue).
- **Red first:** `NativeStartIT` against a missing executable; the package test against an unmarked package.

Measured once, in a throwaway project on this machine (Spring Boot 4.1.2, web and SSE, GraalVM 25.4.4.1.1,
`-march=x86-64`): native-image 59 s of a 69 s Maven run, peak build memory 4 GiB, executable 65 MiB, started in
0.03 s, 73 MB resident; JSON and an SSE event answered from the native executable.

## Acceptance

- The native executable builds, starts and answers in the Build and locally; the instruction-set check names
  `x86-64` and fails on an executable built for the host CPU.
- The JVM tests pass without `-Pnative`.

## As built

- **Modules:** `planner/core`, `planner/store/api`, `planner/store/git` (under the grouping `planner/store`),
  `planner/app` and `planner/test-support`, all under the grouping `planner`; every main package `@NullMarked` and
  checked by NullAway, held by `NullMarkedPackagesTest`, seen red on an unmarked package.
- **JUnit 6 from Spring Boot's BOM**, imported before porelo-parent's JUnit 5 set: Spring Boot's test support fails on
  JUnit 5 with a `NoSuchMethodError`.
- **`GET /health`** of the app's own, no Actuator; the app listens on `127.0.0.1` only.
- **`-Pnative`** in `planner/app`: Spring's AOT step, `native-maven-plugin` with `-march=x86-64`, then
  `porelo-cpu-check` on `target/porelo-planner`, then `NativeStartIT` through failsafe. Seen red: the CPU check on an
  executable built with `-march=native`; the start test on a missing executable and on one that exits at once.
- **The reachability metadata** comes as the Maven artifact `graalvm-reachability-metadata` at the native plugin's
  version, pinned like every Maven dependency; nothing else is downloaded.
- **Measured on this machine:** `-Pnative clean verify` 98 s, native-image 75 s, peak build memory 3.96 GiB,
  executable 65 MiB.
- **`build.yml`** runs `-Pnative verify` and the pushed commits' messages; it publishes nothing yet.
- **The loopback binding is tested:** `PlannerStartsTest` fails when the planner can be reached on another IPv4
  address of the computer (seen red without `server.address`), and fails when the computer has none to try.
- **The native start test** waits for a `200` and fails when the executable does not stop within five seconds of
  `SIGTERM` (seen red on a stand-in that answers and ignores it).
- **The reading** (agy, Gemini, read-only): four notes, no blocking finding. The three on the tests are fixed as
  above. The fourth - the Build runs on a pull request that changes documents alone - is declined: the trigger is the
  one every Porelo repository's Build has, and a pull request's documents are checked by the Shared rules check.
