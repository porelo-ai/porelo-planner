# PL54 — The Planner Keeps A Log Beside The Window's

```json
{
  "$schema": "issue-1.json",
  "id": "PL54",
  "title": "The Planner Keeps A Log Beside The Window's",
  "state": "Review",
  "size": "M",
  "priority": "High",
  "rank": 27,
  "blockedBy": [],
  "covers": "The planner writes what it does and what only informs - its start and address, each store read and the faults it left out, each save, each task's start and end - to a log file of bounded size beside the window's log, at the level a person chose, never with a credential in it"
}
```

**Size:** M - the log directory checked as the planner's own, the log file set up in code for the native executable
on Linux and Windows, entries at the planner's main steps in `app` and `store/git`, one field in the address file the
window reads, and guards that no credential and no forged line reaches the file.

**Estimate:** 67 min, the measured median of M. Uncertain: whether Logback's rolling file needs reachability metadata
in the native image, known only from the native build; the Windows executable's log is seen only on Windows.

**What must be true.** A person who wants to know what the planner did - why a board was read with faults, when a
save went out, why a task did not follow - finds it in the planner's log file, which keeps its newest lines within a
bound and never holds a credential.

## Plan

- The planner logs through SLF4J with Logback, as Spring Boot brings it. `core` has no logging and keeps none: it is
  plain Java, so its steps are logged where `app` calls them.
- **Where.** The log file is `planner.log` in the folder the window keeps its `frontend.log` in:
  `$XDG_STATE_HOME/porelo/logs` on Linux (its XDG default under the home without it), `%LOCALAPPDATA%\porelo\logs` on Windows.
  `PrivateFiles` has a fourth kind, `LOGS`, with the same base as the state on Linux and `%LOCALAPPDATA%` on Windows,
  and the steps `porelo` and `logs`. Only `logs/` must be the account's alone - owned by it, `0700` or one access
  entry for the account with inheritance removed, reached through no link; `porelo/`, which other parts of Porelo
  share, may be readable by others, and is held to the rules of a directory on the way: the account's or root's, not
  writable by the group or others, no link. Whoever comes first makes `logs/` private; the planner never loosens or
  repairs one another part made, and refuses one that is not private, naming it. The location is decided in one
  place, `LogFile.directory`; `planner.log.base` replaces the platform's base, for tests.
- **When.** The log file is opened only after the address file's lock is taken - the `LogFile` bean takes the
  `AddressFile` bean - so a second planner that fails to start never touches the first one's log. Spring's own logging
  starts before that and stays on the console; the planner adds its file to Logback in code, so no logging XML and no
  `logging.file.name` is involved. A log directory or file that cannot be made or checked stops the start with a
  message naming it. The planner creates `planner.log` itself with `PrivateFiles.ownerOnlyFile()` and checks it with
  `requireOwnFile(file, true)` before Logback opens it, at the start and after every roll-over, since an open by name
  takes the umask's mode. The mock mode writes no log file: it reaches no store and must not mix into the person's
  log.
- **Bound.** `planner.log` rolls over at `planner.log.max-file-size` (10 MB) into `planner.1.log` to
  `planner.<max-history>.log` (3), so at most 40 MB are kept; the size is checked at every line, not once a minute as
  Logback does by default. One value in a line is cut to 200 characters, and a refused call logs only its kind.
- **What reaches the file.** Only the loggers under `org.fuin.porelo.planner`, whose level the property
  `logging.level.org.fuin.porelo.planner` sets (INFO until the window sets one); the root stays INFO and its lines
  stay on the console, since Spring's and Tomcat's loggers at DEBUG can write a call's Authorization header. Every value from a repository, Git or
  the window - names, titles, faults, messages - goes through `LogText.of` in `store/git`, which replaces control,
  format and separator characters (CR, LF, tab, ESC, bidirectional marks, ...) with `?` and cuts the value, so one
  entry is one line. A line has time, level, class and words, never a stack trace.
- **Entries.** INFO: the start with its port and address file; each board read, once per project and revision, with
  its faults, each on a line of its own (`LoggedStore` around every opened store, so a call, a poll and a work step
  are logged alike); a project given; each save of moves, a new issue, a worker and a priority, with the revision it
  made; the tasks offered for a story, a task's report and a stopped work; a refused call by its kind and status; a
  call without the token by that fact alone. WARN: a store that could not be read, reached, logged into or pushed to,
  with the store's words; a poll that failed; a work record that is unavailable. DEBUG: a stream's end, and each Git
  call in `GitRunner` by fixed fields only - the subcommand, its duration and, on a failure, its kind and its message,
  already passed through `GitRunner.scrubbed`. A `GitCall` (whose record `toString` holds the whole environment) and
  Git's raw arguments are never logged.
- **Address file.** `planner.json` gets a field `log`, the log file's absolute path, so the window can open the log
  of a planner that stopped serving; the file lies directly in the logs folder and may not exist until the planner
  first writes. The mock mode, which keeps no log, writes no `log` field. The address file is written by Jackson, so
  a path with `"` or `\` stays one string.
- Tests: unit in `store/git` - `LogDirectoryTest` (a log directory others can read or write refused, a shared
  `porelo/` of `0755` taken, one the group can write or a link refused), `LogTextTest` (a line break or control
  character leaves one line, a long value cut), `GitRunnerLogTest` (a credential in the environment or in a remote's
  address reaches no line at DEBUG); unit and integration in `app` - `LogFileTest` (the file `rw-------`, rolled over
  within its bound with every file `rw-------`, a link in its place refused), `AddressFileTest` (a log path with a
  quote and a backslash), `LogLinesTest` (a read with a fault and a save each leave a line, a fault's forged line stays
  in its entry), `PlannerLogTest` (the address file names the log; after an authorized and an unauthorized call at
  DEBUG, neither token is in the file; the mock mode writes no log and names none).

## Acceptance

- After a read with a fault and a save, the log file holds a line for each, with time, level and words, the fault on
  one line however many line breaks it carries; past its bound the oldest lines are gone and at most four files of
  the bound's size remain, each `rw-------`; neither the planner's token nor a wrong one, nor a credential given to
  Git in its environment or a remote's address, appears in any line at DEBUG; the address file names the log; the
  mock mode writes none; the native executable writes the same file on Linux and Windows.

## To be checked

- The native build, once: Logback 1.5.38 builds the converters of its file name pattern and of the line pattern from
  `Supplier` maps (`FileNamePattern.CONVERTER_MAP`, `Compiler`), read in its bytecode, and Spring Boot 4.1.2's
  `LogbackRuntimeHints` registers `IntegerTokenConverter` and `DateTokenConverter` for reflection besides, so the
  rolling file should need no reachability metadata of its own; the log file is set up in code, with no XML for
  Joran to read. Only the native build and a start of the executable show it.
- The Windows executable's log, seen only on Windows: `%LOCALAPPDATA%\porelo\logs` made with one access entry for the
  account, a `porelo\` the window made held to the rules of a directory on the way.
