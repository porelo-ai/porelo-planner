# PL30 — The Screen Finds The Planner On Windows

```json
{
  "$schema": "issue-1.json",
  "id": "PL30",
  "title": "The Screen Finds The Planner On Windows",
  "state": "Backlog",
  "size": "M",
  "priority": "Medium",
  "rank": 19,
  "blockedBy": ["PL29"],
  "covers": "The screen package's HTTP source reads planner.json from %LOCALAPPDATA%\\porelo-planner on Windows and refuses one another account could read, replace or redirect, checked through the file's owner and access control list"
}
```

**Size:** M - one class of the screen package, but the address file's place on Windows is a contract with the
window, and its checks run on a second platform.

**What must be true.** On Windows, the window's planning screen finds the planner of the same account and nobody
else's. It reads the planner's address and token from `%LOCALAPPDATA%\porelo-planner\planner.json`, and refuses the
file when another account could have written it or could read the token.

## Plan

- **`PlannerAddress.read`** in `screen/lib/src/http/planner_source.dart` branches on the platform.
  - **On Linux** it stays as it is.
  - **On Windows** the base is `LOCALAPPDATA`, or the runtime directory the caller passes. It must be an absolute path
    on a drive; a UNC path, a relative path or `..` is refused with `NoAddress` or `BadAddress`, as on Linux.
- **The checks are the planner's own, read through one call.** Dart has no access control lists, so one
  `powershell.exe`, taken by its full path under `%SystemRoot%\System32`, prints:
  - the process's own account SID;
  - for each path from the drive's root down to `planner.json`: its owner SID, its DACL in SDDL, and whether it is a
    reparse point.

  SDDL and SIDs do not depend on the system's language. The Dart side parses them and applies the same rules the
  planner applies when it writes the file:
  - an ancestor that another principal may rename, delete or re-permission is refused;
  - `porelo-planner` and `planner.json` must be the account's, with no entry for anyone but the account, `SYSTEM`
    and Administrators;
  - a reparse point is refused.

  The call is injected as `stat` is today, so the rules are unit-tested on Linux over fixed outputs.
- **Tests, red first.**
  - Over fixed outputs: a foreign owner, a foreign read entry on `planner.json`, a junction, an ancestor others may
    rename, a UNC base, and the accepted defaults.
  - A test tagged `windows` reads a real file the test lays down with the planner's DACL, and is seen red with one
    extra entry. It runs in the Windows job's own step of the screen's tests.

## Acceptance

- On the Windows runner, the screen reads a `planner.json` that `porelo-planner.exe` wrote, and refuses the same
  file after another account's read entry is added.
- The Linux behavior and its tests are unchanged.
- Each new guard is seen red by a break that compiles.

## To be checked

- How long the PowerShell call takes on the runner. The window reads the address once per reach, never per request.
