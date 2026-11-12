# PL81 — The Push Check And Stage Commands In The Planner

```json
{
  "$schema": "issue-1.json",
  "id": "PL81",
  "title": "The Push Check And Stage Commands In The Planner",
  "state": "Implementing",
  "size": "L",
  "priority": "High",
  "rank": 1,
  "blockedBy": ["PL60"],
  "covers": "The checks of the pushes to main and the agents' stage commands are given by the planner's command line, `check-pushes` and `stage`, with the build tools' rules, fault lines and exit codes, so the build tools hold none of the work format"
}
```

**Size:** L - about 1,300 lines of commands and 1,400 of tests taken over, and a token for GitHub's API read from the
environment, a trust boundary.

**What must be true.** Every repository's build checks its pushes to `main`, and an agent records its stages, with the
planner's command line, with the same rules, fault lines and exit codes as the build tools' `check-pushes` and `stage`
give today.

- **`check-pushes --since <hours>`**: each push to `main` in the window read from GitHub's repository activity with
  `java.net.http`, the token from the environment only, never printed, never in an argument or an error; pages, the
  push kinds and the refusal of a full page as today; each push's range checked as `check-commits` checks it.
- **`stage begin|end <issue> <stage> ...` and `stage median|p80 <size>`**: the options, the one open stage per agent,
  the session file's tokens and the refusal of `begin ... move` as today, written by the planner's `StageRecords`.
- What it takes over, measured in the build tools: `CheckPushes` 247 lines and its test 261, `Stage` 824 and its test
  1,057, `StageTokens` 240, `StageFixtures` 57.
