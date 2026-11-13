# PL30 — The Screen Finds The Planner On Windows

```json
{
  "$schema": "issue-1.json",
  "id": "PL30",
  "title": "The Screen Finds The Planner On Windows",
  "state": "Review",
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

- **`PlannerAddress.read`** in `screen/lib/src/http/planner_source.dart` branches on the platform, injected as
  `windows` (default `Platform.isWindows`) with the `environment` (default `Platform.environment`), so both branches
  are unit-tested on Linux. `HttpPlanningSource.fromAddressFile` keeps its signature: `runtimeDirectory` is the base
  on both systems.
  - **On Linux** it stays as it is.
  - **On Windows** the base is `runtimeDirectory`, or else `LOCALAPPDATA`, its name read in any case as the planner
    reads it. It must be a drive's absolute path in its plain form, as the planner's `drivePath` holds it: no UNC,
    device or `\\?\` path, no `/`, no empty, `.` or `..` step, no `:` past the drive, which would name a file's
    stream, and no control character. Unset or blank is `NoAddress`; any other refused form is `BadAddress`.
  - **The base is where it says**, as the planner's `toRealPath` comparison holds it: resolved, it must equal itself,
    so a link, a substituted drive or a mapped share is refused before Windows is asked.
- **The checks are the planner's own, read through one call in the planner's own form.** Dart has no access control
  lists, so one `powershell.exe`, taken by its full path under a `SystemRoot` held to the same drive-path form, runs
  the read half of the planner's script (`WindowsPrivateFiles.SCRIPT`), the paths given through `PORELO_PATHS` in the
  environment and never as code. It is killed past 30 s or once either output passes 1 MiB, and nothing waits on its
  outputs after that; the address file's read on Windows has a deadline of its own. It prints `self <sid>`, then per path
  `path <i> <owner sid> <reparse 0|1> <d|f>` and its `ace <i> <A|D> <sid> <rights> <inheritance> <propagation>` lines,
  inherited entries included, all by SID, so the system's language does not matter.
  - A Dart `WindowsAccess` parses it exactly as the Java one does - any line out of form, a missing path or a second
    `self` refuses the whole answer - and applies the planner's two rules:
    - **a safe ancestor** - the drive's root down to the base's parent, and the base itself: a directory, no reparse
      point, owned by the account, `SYSTEM`, Administrators or TrustedInstaller, and no allow entry that applies to
      the path itself giving anyone else delete, delete-child, write-DAC, write-owner, generic-all or generic-write;
    - **the owner's alone** - the base (as the planner holds it), `porelo-planner` and `planner.json`: the right
      kind, no reparse point, owned by the account, `SYSTEM` or Administrators, and no allow entry for anyone else,
      inherit-only ones included; `porelo-planner` also passes the account's full control on to what is made in it.
  - A failed or late call, or no `planner.json`, is `NoAddress` (no planner runs), as a failed `stat` is on Linux; an
    answer out of form or a broken rule is `BadAddress`. Only then is the file opened and read, bounded as on Linux.
- **Tests, red first.**
  - Unit, over fixed answers in the planner's form: the accepted default; a foreign owner of `planner.json`; a
    foreign read entry on `planner.json`; an inherit-only foreign entry on `porelo-planner`; a junction (reparse
    point) at `porelo-planner`; an ancestor another account may rename (delete-child); a UNC base, a relative base,
    a `..` step and a missing `LOCALAPPDATA`; an answer with a line out of form or a path missing.
  - On Windows, `@TestOn('windows')` and tagged `windows`: a real directory the test lays down with the planner's
    list (the planner's own protect half of the script), a `planner.json` in it, read through the real
    `powershell.exe`; seen red with one extra read entry for Everyone. It runs in a new job of the Build,
    `screen-windows`, on `windows-2025`, `flutter test --tags windows` in `screen`, beside the Linux screen job and
    apart from the planner's Windows job; Flutter alone there, since that job needs no JDK.
- **What the plan reading asked, and why not.**
  - A `SystemRoot` or `PSModulePath` planted in the environment, choosing another PowerShell or its modules: the
    environment is the account's own, which no other account sets; the planner takes PowerShell the same way, and
    Porelo does not protect the person from their own settings.
  - A planner that has ended, whose port another account binds before the window reads its file again: the file the
    planner leaves only after a crash is still its own, and the same holds on Linux; the token of an ended planner
    opens nothing. Proving the listener is the planner, before any project is named to it, is the planner's and the
    window's contract on both systems, and goes to an issue of its own.

## Acceptance

- On the Windows runner, the screen reads a `planner.json` that `porelo-planner.exe` wrote, and refuses the same
  file after another account's read entry is added.
- The Linux behavior and its tests are unchanged.
- Each new guard is seen red by a break that compiles.

## To be checked

- How long the PowerShell call takes on the runner. The window reads the address once per reach, never per request.
