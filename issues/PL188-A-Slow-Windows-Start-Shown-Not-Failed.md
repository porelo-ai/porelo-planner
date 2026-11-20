# PL188 — A Slow Windows Start Shown, Not Failed

```json
{
  "$schema": "issue-1.json",
  "id": "PL188",
  "title": "A Slow Windows Start Shown, Not Failed",
  "state": "Backlog",
  "size": "S",
  "priority": "Highest",
  "rank": 1,
  "blockedBy": [],
  "covers": "The native start on Windows logs how long each protection of its directories took, and the start test's bound on Windows lies above the planner's own PowerShell limit, so a slow runner shows as a slow line, not a red Build"
}
```

**Why this size.** S: one class of the Git store logs a duration, one test's bound changes, both in one module each,
no contract.

**What must be true.** A Build on Windows fails only when the planner does not start, not when a runner's PowerShell
is slow, and the log says how long the start waited on it.

## Plan

- Seen on main's Windows job: `NativeStartIT.theNativeExecutableServesTheMockBoardWithMock` found no address file
  within its 60 s, while three other native starts of the same executable passed in the same job. The planner's log
  stopped after the web context was initialized, with the process alive: the next step on Windows makes and protects
  the log, state and address-file directories through `WindowsPrivateFiles`, which starts `powershell.exe` per
  protection with a limit of 60 s (`WindowsPrivateFiles.LIMIT`); the `--mock` start has a fresh `LOCALAPPDATA`, so
  every protection runs. The test's bound (`NativeStartIT.START`, 60 s on Windows) equals one call's limit.
- `WindowsPrivateFiles.powershell` logs each call's duration at INFO, without the paths' contents.
- `NativeStartIT.START` on Windows becomes larger than the sum of the protections a fresh start runs at their limit,
  and the failure message names the durations the log shows.
- No harm test: a log line and a test's bound; the Windows job of the Build shows the new line.

## Acceptance

- The Windows job's log of a native start shows each protection's duration, and the test passes on a runner whose
  PowerShell starts slowly.
