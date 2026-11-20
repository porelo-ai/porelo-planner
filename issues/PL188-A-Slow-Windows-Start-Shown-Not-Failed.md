# PL188 — A Slow Windows Start Shown, Not Failed

```json
{
  "$schema": "issue-1.json",
  "id": "PL188",
  "title": "A Slow Windows Start Shown, Not Failed",
  "state": "Implementing",
  "size": "S",
  "priority": "High",
  "rank": 1,
  "blockedBy": [],
  "covers": "The native start on Windows logs how long each protection of its directories took, and the start test's bound on Windows lies above the planner's own PowerShell limit, so a slow runner shows as a slow line, not a red Build; the start logs each PowerShell call so the next Windows Build measures the slow call, whose cause is still open"
}
```

**Why this size.** S: one class of the Git store logs a duration, one test's bound changes, both in one module each,
no contract.

**What must be true.** A Build on Windows fails only when the planner does not start, not when a runner's PowerShell
is slow, and the log says how long the start waited on it.

## Plan

- Seen on main's Windows job: `NativeStartIT.theNativeExecutableServesTheMockBoardWithMock` found no address file
  within its 60 s, while the other native starts of the same executable passed in the same job. The planner's log
  stopped after the web context was initialized, with the process alive.
- Counted in the code: every call of `PrivateFiles.directories` on Windows is one `powershell.exe` call with a limit of
  60 s, and `PoreloExecutable.OnWindows.checked` is one more. A start with `--mock` makes one: `AddressFile.open`
  (the runtime directory); its log file is none, its log level and work record are kept in memory. A start without
  it makes three: `AddressFile.open` (runtime), `LogFile.directory` (logs) and `LogLevel.kept` (state), and a fourth
  through `ForgeTokens.of` when `PORELO_EXECUTABLE` is set. A project's work record and cache directory are checked
  only when the project is opened.
- So the start that failed made the fewest calls, one, and the starts with three passed: the sum of several calls is
  not the cause, and gathering them into one call would not have saved this start. The one call took longer than the
  test's bound less the process's own start; why it did is not known. That start is also the only Windows start
  whose environment the test clears to `SystemRoot`, `PATH` and a fresh `LOCALAPPDATA`, which PowerShell inherits;
  whether that makes it slow is a guess, not measured.
- `WindowsPrivateFiles.timed` logs at INFO when each PowerShell call starts and how long it took, naming what it
  checks (the kind of directory, or Porelo's executable), never a path.
- `NativeStartIT` bounds a start on Windows by the start's own PowerShell calls at their limit plus 30 s (one with
  `--mock`, three without), so the planner's own refusal, not the test's bound, ends a slow start; its failure names
  the last PowerShell line the planner logged.
- The `--mock` test keeps `TEMP`, `TMP`, `USERPROFILE` and `APPDATA` from the test's own environment, with its own
  fresh `LOCALAPPDATA`: every other Windows start inherits them, so the guess is tested in the next Windows Build.
- No harm test: a log line, a test's bound and a test's environment; the Windows job of the Build shows the new lines.

## Acceptance

- The cause counts as measured when the Windows jobs show, for each native start, the line `PowerShell checked the
  runtime directories in N ms`: with the kept environment, the `--mock` start's N in the range of the other starts'
  over several Builds, and no `--mock` start missing its bound; a `--mock` start still slow with the kept environment
  says the guess is wrong, and its line names the call it waited on.
