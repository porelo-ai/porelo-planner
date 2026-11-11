# PL55 — The Window Sets The Planner's Log Level

```json
{
  "$schema": "issue-1.json",
  "id": "PL55",
  "title": "The Window Sets The Planner's Log Level",
  "state": "Review",
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

**Estimate:** 67 min, the measured median of M.

**What must be true.** A person who raises the level in the window's settings gets more lines in the planner's log at
once, without restarting anything, and the level holds after the planner starts again.

## Plan

- A resource `/log/level` in `planner.yaml`, schema `Level`: `GET` answers `{level}`, the level in force; `PUT` takes
  `{level}`, one of `ERROR`, `WARN`, `INFO`, `DEBUG`, and answers the level now in force; any other is refused as
  `InvalidLevel` (400) and the level in force stays. Only the planner's owner reaches it, as every other resource
  (`AccessFilter`). Where the file lies is the address file's `log` field, not this resource's.
  `ApiMatchesDescriptionTest` holds `LogLevelController` to the description; the mock mode serves the resource too,
  keeping the level in memory only.
- `LogLevel` sets the level through Spring Boot's `LoggingSystem` for the planner's own loggers
  (`org.fuin.porelo.planner`) alone, never the root: a library's logger at DEBUG could write a call's Authorization
  header, and only the planner's lines reach the file anyway. It keeps the level in `log-level` in the planner's state
  directory (`PrivateFiles`, kind `STATE`), written to a new owner-only file and moved into place, and sets the kept
  one when its bean is made, right after the log file opens. Without a kept level, or with one naming none of the
  four, the configured level stays, INFO unless a start names another. The property `planner.state.base` replaces
  the state directory's base, for tests.
- The window sets its own level and the planner's from one setting; the planner does not read the window's.
- Red first, integration in `app` (`LogLevelTest`): a `PUT` of DEBUG makes a DEBUG line of the planner's appear in
  the log file, not one written before, and a `GET` answer DEBUG; a planner started again over the same state
  directory answers DEBUG; TRACE is refused with 400 and nothing is kept. Seen red with the level not set, not kept,
  and an unknown level taken.

## Acceptance

- The window's level setting changes the planner's log at once and after a restart; an unknown level is refused.
