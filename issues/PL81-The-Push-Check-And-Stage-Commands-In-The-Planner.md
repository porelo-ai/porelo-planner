# PL81 — The Push Check And Stage Commands In The Planner

```json
{
  "$schema": "issue-1.json",
  "id": "PL81",
  "title": "The Push Check And Stage Commands In The Planner",
  "state": "Review",
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

## Plan

What stands today, read in the code:

- The build tools' `CheckPushes` reads `GET https://api.github.com/repos/<owner>/<name>/activity?ref=refs%2Fheads%2Fmain&per_page=100`
  through their `Web`, which follows redirects with the same headers; the token goes only to `https`
  `api.github.com`, and only where `GITHUB_SERVER_URL` is unset or GitHub's. It refuses a `GITHUB_API_URL` that is
  not GitHub's, a `GITHUB_REPOSITORY` that is no `owner/name`, a token unset or holding a space or control
  character, an answer that is no list, an entry without a time or a full hash, and a full page whose oldest entry
  is still inside the window. Each push of the kinds `push`, `force_push`, `pr_merge`, `merge_queue_merge` and
  `branch_creation` to `refs/heads/main` is checked as `before..after`, or `after` alone where `before` is all
  zeros, oldest first, the worst exit code being the answer; every message has the token replaced by `<token>`.
- `Stage` (`stage begin|end <issue> <stage> [options]`, `stage median|p80 <size>`) keeps one open stage per agent,
  or per subagent session by the session path's SHA-256, under `<git-dir>/porelo-stage/`, behind a `tryLock` shared
  by all actors, refusing a symbolic link anywhere below the root. `begin` checks the issue's head, the size a
  measured stage needs, no open agent stage in `stats/`, and appends an open record; `end` matches the open stage,
  fills `status_after`, `result`, `end`, `wall_min`, `vm_min` and, from a session file, the tokens through
  `StageTokens`, and replaces the line atomically. Every failure is exit 2 with a fixed line that quotes no session
  content. A `move` is no stage it knows, so `begin ... move` is refused.
- The planner holds the record format in `StageRecords` (`read`, `move`, `statistic`), the issue's state and size
  in `CommitRules.issue`, and `check-commits` in `CommitRules`.

The steps, all in `planner/store/git`, so nothing outside it knows Git or the forge, and `WorkCommands` only calls
them, with every line escaped as for `check-commits`:

1. `StageCommand` holds the command: the private state, the lock, the session file and the clock, and checks every
   record it writes by `StageRecords`' own rule; `move` stays the only writer of a person's record. `StageTokens` is
   copied as it is, on Jackson.
   Stricter than the build tools where they fail open or hang, as the plan's reading found:
   - The git directory is `<root>/.git` or a linked worktree's `worktrees/<name>` below a `.git` directory, and git
     runs without `GIT_DIR`, `GIT_WORK_TREE`, `GIT_COMMON_DIR` and `GIT_INDEX_FILE` from the caller's environment.
   - A session file is a regular file, no symbolic link and no FIFO, of at most 64 MB, checked again at `end`
     before it is read; a reader's fault quotes no content of it.
   - `begin` writes the private state, then the record, as the build tools do; a private state whose record is
     missing from `stats/` is cleared by the next `begin` of that actor, so a crash between the two never locks the
     actor out. Writing the record first would leave an open record without its state, which the next `begin`
     refuses as an own stage already open.
2. `PushRules`, in a package `pushes` of its own, since the work format in `stories` reaches no network, reads the
   activity through a small `Activity` interface; its implementation uses `java.net.http`
   with `Redirect.NEVER`, a connect timeout of 10 seconds, a 60-second deadline over the whole answer, a body of at
   most 4 MB, and Jackson; only a 2xx answer is read, and every other status, a redirect among them, is no answer.
   The token is read from the environment only, sent only as the `Authorization` header to
   `https://api.github.com`, never handed to a child process (git runs without `GITHUB_TOKEN`), and every message
   is redacted. Measured: none of the git commands the checks run (`log`, `ls-tree`, `diff-tree --name-only`,
   `rev-list`, `show <commit>:<path>`) starts a configured program, so the token is withheld without a test that
   could show it reaching one. A `GITHUB_SERVER_URL` set to anything but `https://github.com` is no answer, as is an owner or name
   that is `.`, `..` or starts with `.`. Each range goes to `CommitRules.check`.
3. A size's median and 80th percentile take the build tools' rule: the last entry of an issue, a person's move
   among them, says whether it ended, and the minutes come from the agent's stages alone, so an issue a person
   moves to Finished counts and one moved back out does not.
4. `WorkCommands` dispatches `check-pushes --since <hours>` and `stage ...` with the build tools' usage lines and
   exit codes. `doc/index.md`, the app's README and a changelog entry describe them.

Tests, red first:

- Unit, `PushRulesTest`: the build tools' `CheckPushesTest` carried over over a fake `Activity`. Added: a token
  never in any line said, also when the answer quotes it; a redirect, a 403 and a 500 answered as no answer, not
  followed; an over-long body no answer; a foreign `GITHUB_SERVER_URL` and an owner `..` no answer; git's
  environment without the token.
- Unit, `StageCommandTest`: the build tools' `StageTest` carried over on a temporary repository made with git under
  `GIT_CONFIG_GLOBAL=/dev/null`, with a fixed clock. Added: `begin ... move` refused; a record `begin` writes
  read back by `StageRecords.read`; a `.git` file naming a directory elsewhere, another checkout's linked worktree
  storage and `GIT_DIR` refused; a session
  that is a symbolic link, a FIFO or over 64 MB refused at `begin` and at `end`; a private state without its record
  cleared by the next `begin`.
- Unit, `StageRecordsTest` and `GitStoreSaveTest`: a move out of Finished takes an issue out of the median, a move
  into it puts it in.
- Integration, `StageCommandProcessTest` in `app`, test scope while the build tools still carry the commands: both
  run as processes, each on its own copy of one fixture repository, with the same exit codes, lines and records
  (times and measured minutes masked) for 14 scenarios of `stage` and `check-pushes`, among them a begin and end, a
  second open stage, an end without a begin, a move, an unknown actor and issue, both statistics, and
  `check-pushes` without a token or with a window of 0.

## Acceptance

- The tests above, each seen red first; the full build green.
- The planner's executable gives `check-pushes` and `stage` with the build tools' exit codes, on one fixture run by
  both.
