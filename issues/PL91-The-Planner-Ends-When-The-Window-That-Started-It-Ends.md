# PL91 — The Planner Ends When The Window That Started It Ends

```json
{
  "$schema": "issue-1.json",
  "id": "PL91",
  "title": "The Planner Ends When The Window That Started It Ends",
  "state": "Review",
  "size": "S",
  "priority": "High",
  "rank": 32,
  "blockedBy": [],
  "covers": "A planner the window started ends by itself when the window's end of the pipe it was started with closes, a hard kill of the window included, so no planner of an older install keeps running beside a newer window"
}
```

**Size:** S - one option of the planner's start in `app` and its test; the window's side is its own issue.

**Estimate:** 33 min, the measured median of S.

**What must be true.** A planner the window started ends by itself as soon as the window is gone, also when the window
is killed hard and runs none of its own ending; a planner started by hand runs on as today.

## Decided by the operator (2026-11-12)

- The planner ends itself when the window's pipe closes; a hard kill of the window closes it too.

## Plan

- **Today.** The planner reads nothing on its standard input and runs until it is stopped; a window that died left a
  planner from an older install running beside a newer window (snapshot.21 found running a day after snapshot.36 was
  installed).
- **The option.** A start option, `--end-with-input`, given by the window, which starts the planner with its standard
  input as a pipe it holds. A daemon thread reads the input until end of file and then closes the application context,
  so the planner ends as on a stop: the address file and its lock go, and saves already committed are pushed on the next
  start (a save answers once its commit stands). Without the option, nothing reads the input.
- **Tests, red first.** Integration, `app`: a planner started with the option and a pipe ends within seconds of the pipe
  closing and removes its address file; one started without the option keeps running when its input closes. Harm: a
  planner of an older build serving a newer window, as the operator met. The native executable's start test
  (`NativeStartIT`) gains the same case, since the window starts the native one.
- **The code's reading.** A stop that fails, or an input that breaks instead of closing, still ends the process, with
  code 1 and a line at ERROR or WARN; a closed input ends it with code 0, and the tests read that code. The code is
  taken by a thread that is no daemon, since once the stop has closed the server nothing else keeps the process up
  long enough to take it, and it ended with 0 whatever the stop answered. The tests' fixed waits of 15 seconds stay:
  they make a run slower but cannot turn it red falsely.

## Acceptance

- With the option, closing the planner's input ends it and removes its address file; without it, the planner runs on;
  both seen on the JVM and the native executable, the first test seen red.
