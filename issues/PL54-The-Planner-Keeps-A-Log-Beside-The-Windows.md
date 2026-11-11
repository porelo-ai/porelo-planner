# PL54 — The Planner Keeps A Log Beside The Window's

```json
{
  "$schema": "issue-1.json",
  "id": "PL54",
  "title": "The Planner Keeps A Log Beside The Window's",
  "state": "Idea",
  "size": "M",
  "priority": "High",
  "rank": 27,
  "blockedBy": [],
  "covers": "The planner writes what it does and what only informs - its start and address, each store read and the faults it left out, each save, each task's start and end - to a log file of bounded size beside the window's log, at the level a person chose, never with a credential in it"
}
```

**Size:** M - the logging set up for the native executable on Linux and Windows, entries at the planner's main
steps, and a guard that no credential reaches the file.

**What must be true.** A person who wants to know what the planner did - why a board was read with faults, when a
save went out, why a task did not follow - finds it in the planner's log file, which keeps its newest lines within a
bound and never holds a credential.

## Plan

- Today the planner logs through SLF4J with Logback, as Spring Boot brings it, to its standard output only, which the
  window does not keep; `EventsController` alone writes a line, at DEBUG.
- The log file is `planner.log` beside the window's `frontend.log`, in one directory both use:
  `$XDG_STATE_HOME/porelo/logs` on Linux, `%LOCALAPPDATA%\porelo\logs` on Windows. The planner makes it, or checks it
  when the window made it first, with `PrivateFiles` as it does its own state: each directory the account's alone
  (0700, or one access entry for the account), owned by the account, reached through no link. Both programs make it
  that way; a directory others may read or write is refused, and the planner's start fails naming it, as it does for
  its state directory, since the log names the person's projects and repositories. Spring Boot's own properties
  (`logging.file.name`, `logging.logback.rollingpolicy.max-file-size`, `max-history`) bound the file to the newest
  10 MB and three older files, with no XML configuration the native image would have to carry. The level comes as
  the person set it in the window, INFO by default.
- The address file `planner.json` gets a field `log`, the log file's absolute path, so the window can open the log
  of a planner that stopped serving; the file may not exist until the planner first writes. A change to the
  contract with the window, agreed in the channel.
- Entries: INFO for the start with its address file, each store read with its faults (`Board.faults`), each save and
  its commits, each task offered, started and ended; WARN for a refused save, a store that could not be read, a work
  record that is unavailable; DEBUG for the stream's ends and each Git call. What only informs on the board - an issue
  worked outside this planner, a fault left out - is logged, not raised as needing the person.
- Every Git message passes `GitRunner.scrubbed` before it is logged, as before it is answered.
- Red first in `app`: a store whose Git failure carries a credential in a remote's address leaves no trace of the
  credential in the log file at DEBUG.

## Acceptance

- After a read, a save and a task's end, the log file holds one line each with time, level and words; past its bound
  the oldest lines are gone; a credential given to Git appears in no line at any level; the native executable writes
  the same file on Linux and Windows.

## To be checked

- Whether the native image needs reachability metadata for Logback's rolling policy, measured with the native build.
