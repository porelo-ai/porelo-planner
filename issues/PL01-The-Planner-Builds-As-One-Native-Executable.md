# PL01 — The Planner Builds As One Native Executable

**Status:** Backlog.
**Size:** M - four modules and their build, a native image with its instruction-set check, the Build workflow and
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

## To be checked

- Whether the Spring AOT step needs the GraalVM reachability metadata repository at build time, as the measurement
  did (it downloaded it), and how it is pinned by version and digest like every other download.
- Peak build memory of 4 GiB against the GitHub runner's.
