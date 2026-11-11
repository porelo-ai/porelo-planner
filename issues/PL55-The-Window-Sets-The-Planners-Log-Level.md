# PL55 — The Window Sets The Planner's Log Level

```json
{
  "$schema": "issue-1.json",
  "id": "PL55",
  "title": "The Window Sets The Planner's Log Level",
  "state": "Estimated",
  "size": "M",
  "priority": "High",
  "rank": 28,
  "blockedBy": [
    "PL54"
  ],
  "covers": "The window sets the planner's log level - ERROR, WARN, INFO or DEBUG, INFO by default - through the planner's REST API, and a level set there holds from the next start too"
}
```

**Size:** M - a change to the contract with the window: a new resource in the OpenAPI description, its controller,
the mock mode's answer, and the level kept in the planner's state.

**Estimate:** 67 min, the measured median of M. Uncertain: it builds on the log file of [PL54](README.md#pl54), and
whether `LoggingSystem` sets a level in the native image as on the JVM is known only from the native build.

**What must be true.** A person who raises the level in the window's settings gets more lines in the planner's log at
once, without restarting anything, and the level holds after the planner starts again.

## Plan

- A resource `/log/level` in `planner.yaml`: `GET` answers `{level}`, the level in force; `PUT` takes `{level}`,
  one of `ERROR`, `WARN`, `INFO`, `DEBUG`, and answers the level now in force. Only the planner's owner reaches it,
  as every other resource (`AccessFilter`). Where the file lies is the address file's `log` field, not this
  resource's. `ApiMatchesDescriptionTest` holds the controller to the description; the mock mode serves the resource
  too, as it serves every other.
- The level is set through Spring Boot's `LoggingSystem` for the planner's own packages and the root, and kept in a
  small file `log-level` in the planner's state directory (`PrivateFiles`, kind `STATE`), written owner-only and read
  before the context starts at the next start; no file, or one that names no level, means INFO.
- The window sets its own level and the planner's from one setting; the planner does not read the window's.
- Red first, integration in `app`: a `PUT` of DEBUG makes a DEBUG line appear in the log file and a `GET` answer
  DEBUG; a level outside the four is refused with 400; a planner started again over the same state directory answers
  DEBUG.

## Acceptance

- The window's level setting changes the planner's log at once and after a restart; an unknown level is refused.
