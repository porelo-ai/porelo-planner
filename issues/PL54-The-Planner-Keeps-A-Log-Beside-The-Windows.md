# PL54 — The Planner Keeps A Log Beside The Window's

```json
{
  "$schema": "issue-1.json",
  "id": "PL54",
  "title": "The Planner Keeps A Log Beside The Window's",
  "state": "Analyzed",
  "size": "M",
  "priority": "High",
  "rank": 27,
  "blockedBy": [],
  "covers": "The planner writes what it does and what only informs - its start and address, each store read and the faults it left out, each save, each task's start and end - to a log file of bounded size beside the window's log, at the level a person chose, never with a credential in it",
  "openQuestions": 0
}
```

**Size:** M - the log directory checked as the planner's own, the logging set up for the native executable on Linux
and Windows, entries at the planner's main steps in `app` and `store/git`, one field in the address file the window
reads, and a guard that no credential reaches the file.

**What must be true.** A person who wants to know what the planner did - why a board was read with faults, when a
save went out, why a task did not follow - finds it in the planner's log file, which keeps its newest lines within a
bound and never holds a credential.

## Plan

- Today the planner logs through SLF4J with Logback, as Spring Boot brings it, to its standard output only, which the
  window does not keep; `EventsController` and `ChangesRegistry` alone write lines. `core` has no logging and keeps
  none: it is plain Java, so its steps are logged where `app` calls them.
- The log file is `planner.log` beside the window's `frontend.log`, in one directory both use:
  `$XDG_STATE_HOME/porelo/logs` on Linux, `%LOCALAPPDATA%\porelo\logs` on Windows. `PrivateFiles` gets a fourth kind,
  `LOGS`, whose steps are `porelo` and `logs` instead of `porelo-planner`; the planner makes them, or checks them when
  the window made them first, as it does its state: each directory the account's alone (0700, or one access entry for
  the account), owned by the account, reached through no link. A directory others may read or write is refused, and
  the planner's start fails naming it, since the log names the person's projects and repositories. Spring Boot's own
  properties (`logging.file.name`, `logging.logback.rollingpolicy.max-file-size`, `max-history`), set from that
  checked path before the context starts, bound the file to the newest 10 MB and three older files, with no XML
  configuration the native image would have to carry. The level is INFO until the window sets one.
- The address file `planner.json` gets a field `log`, the log file's absolute path, so the window can open the log
  of a planner that stopped serving; the file may not exist until the planner first writes. The screen package's
  reader ignores fields it does not know, so the field breaks no window; the window's use of it is a change to the
  contract with the window, agreed in the channel.
- Entries: INFO for the start with its address file, each store read with its faults (`Board.faults`), each save and
  its commits, each task offered, started and ended (`WorkRequests`, `BoardController`); WARN for a refused save, a
  store that could not be read, a work record that is unavailable; DEBUG for the stream's ends and each Git call. What
  only informs on the board - an issue worked outside this planner, a fault left out - is logged, not raised as
  needing the person.
- Git calls are logged in `store/git` itself (`GitRunner`), since nothing outside it knows Git and
  `GitRunner.scrubbed` is its own; every Git message passes `scrubbed` before it is logged, as before it is answered.
- Red first, integration in `store/git`: a Git failure carrying a credential in a remote's address leaves no trace
  of the credential in the log at DEBUG; unit in `store/git`: a log directory another account can write is refused;
  integration in `app`: after a read and a save the file holds a line for each.

## Acceptance

- After a read, a save and a task's end, the log file holds one line each with time, level and words; past its bound
  the oldest lines are gone; a credential given to Git appears in no line at any level; the native executable writes
  the same file on Linux and Windows.

## To be checked

- Whether the native image needs reachability metadata for Logback's rolling policy, measured with the native build.
- Whether the file's lines carry the address file's token anywhere, such as the start's line; it must not.
