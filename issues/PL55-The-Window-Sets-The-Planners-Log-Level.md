# PL55 — The Window Sets The Planner's Log Level

```json
{
  "$schema": "issue-1.json",
  "id": "PL55",
  "title": "The Window Sets The Planner's Log Level",
  "state": "Idea",
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
and the level kept in the planner's state.

**What must be true.** A person who raises the level in the window's settings gets more lines in the planner's log at
once, without restarting anything, and the level holds after the planner starts again.

## Plan

- A resource `/log/level` in `planner.yaml`: `GET` answers `{level}`, the level in force; `PUT` takes `{level}`,
  one of `ERROR`, `WARN`, `INFO`, `DEBUG`, and answers the level now in force. Only the planner's owner reaches it,
  as every other resource. Where the file lies is the address file's `log` field, not this resource's.
- The level is set through Spring Boot's `LoggingSystem` for the planner's own packages and the root, and kept in a
  small file in the state directory, read at the next start; no file means INFO.
- The window sets its own level and the planner's from one setting; the planner does not read the window's.
- Red first in `app`: a `PUT` of DEBUG makes a DEBUG line appear in the log file and a `GET` answer DEBUG; a level
  outside the four is refused with 400.

## Acceptance

- The window's level setting changes the planner's log at once and after a restart; an unknown level is refused.
