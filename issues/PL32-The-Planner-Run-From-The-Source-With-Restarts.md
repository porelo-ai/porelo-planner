# PL32 — The Planner Run From The Source With Restarts

```json
{
  "$schema": "issue-1.json",
  "id": "PL32",
  "title": "The Planner Run From The Source With Restarts",
  "state": "Review",
  "size": "XS",
  "priority": "High",
  "rank": 3,
  "blockedBy": [],
  "covers": "A Maven profile dev runs the planner from the source on the JVM with Spring Boot DevTools, restarted within a second whenever any module compiles anew, for testing against real projects with the window"
}
```

**Size:** XS - two poms and a README line; nothing that ships changes.

**What must be true.** A person working on the planner runs it from the source against the real projects the window
on the same computer gives it. A change compiled in any module is running a moment later, without building a package
or starting anything by hand.

## Plan

- **The root's profile `dev`** declares the Spring Boot plugin for every module, so the reactor takes
  `spring-boot:run`, and skips that goal everywhere but in the app.
- **The app's profile `dev`** adds Spring Boot DevTools as an optional dependency, names the main class and turns
  DevTools' live reload server off. No other profile has DevTools, so no package or native executable gets it.
- **The README of the app** says how to start it and how to compile a change in a second terminal.
- **After a restart** the planner has a new port and token, as at every start. The window reads them when it reaches
  the planner again.

## Acceptance

- `./mvnw -s settings.xml -Pdev -pl planner/app -am compile spring-boot:run` starts the planner, which writes the
  account's address file.
- A compile of `planner/store/git` restarts it within a second, and the address file is there again.
- The native and dist builds have no DevTools on their class path.
