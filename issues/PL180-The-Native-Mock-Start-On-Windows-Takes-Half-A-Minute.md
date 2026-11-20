# PL180 — The Native Mock Start on Windows Takes Half a Minute

```json
{
  "$schema": "issue-1.json",
  "id": "PL180",
  "title": "The Native Mock Start on Windows Takes Half a Minute",
  "state": "Backlog",
  "size": "S",
  "priority": "Highest",
  "rank": 1,
  "blockedBy": [],
  "covers": "The native planner started with --mock on GitHub's Windows runner writes its address file only after about 30 to 60 s, against well under a second on Linux, so NativeStartIT's 60 s bound fails the Windows Build now and then"
}
```

**Why this size.** S: one start path in `planner/app` measured on the Windows runner and its slow step fixed, with
`NativeStartIT`'s bound; no contract.

**What must be true.** The native planner started with `--mock` on Windows serves its address within a few
seconds, as on Linux, so the Windows Build fails only for a real fault.

## Plan

- Measured: the Windows job of Build 44756342231 failed in `NativeStartIT.theNativeExecutableServesTheMockBoardWithMock`
  ("no address file within PT1M"); the planner logged its web context ready after 72 ms and nothing after. Its
  rerun passed, the class taking 59.41 s; the class took 46.81 s and 49.74 s in the two green Builds before it,
  78.35 s in the red one, about 18 s of it the other three tests. On Linux the class's 7 tests take 18.61 s.
  Inferred, not measured: the mock start alone takes about 30 to 60 s on Windows.
- First step, before any fix: a log line at DEBUG for each step of the mock start after the web context
  (`MockStore`'s board read, `WorkRecord`'s records, `AccessConfiguration`'s runtime directory and address file),
  with its time, and `NativeStartIT` printing the planner's output on a slow start, so one Windows Build shows where
  the time goes. Likely places, read in the code: the Windows checks of the account's private directories
  (`WindowsPrivateFiles`, `WindowsAccess`) on `--mock`'s own base, and the board's JSON read through reflection in
  the native image.
- Then the slow step is fixed, and `NativeStartIT`'s Windows bound (`START`, 60 s) stays as the guard.
- Red first: a test of the slow step's own cost at the lowest level that shows it, once the measurement names it.

## Acceptance

- Three Windows Builds in a row show the mock start under 5 s, read from the new log line.
