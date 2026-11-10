# PL20 — Three Findings In The Screen Package

```json
{
  "$schema": "issue-1.json",
  "id": "PL20",
  "title": "Three Findings In The Screen Package",
  "state": "Review",
  "size": "S",
  "priority": "High",
  "rank": 1,
  "blockedBy": [],
  "covers": "The board shows a refusal's own words; a board that failed loads again when its provider changes; the planner's address is trusted only from directories and a file this account owns and others cannot open"
}
```

**Why this size.** three small changes in one module, the screen package, each with its tests; no contract another
repository reads changes.

**What must be true.** A refusal shows its sentence, never `PlannerUnreachable: {}`; a board that failed once, for
a repository not yet named or one the planner refused, loads again when its provider's projects change; and the
window reads the planner's address only from an absolute runtime directory whose `porelo-planner` directory and
`planner.json` are no symbolic links, owned by this account and closed to others, so a runtime directory pointed at a
shared place cannot hand the window another account's planner.

## Plan

- `planningFailureWords` (`screen/lib/src/planning_board.dart`) answers `PlanningRefusal.description` when it is not
  blank, after the parameters' `detail`, `message` or `reason` and the special wordings, without control characters
  and at most 4096 characters long.
- `PlanningBoards._sync` loads a board of a connected provider again through `changeProject` when it is not live and
  holds a problem; a live board's problem is its workflow's faults.
- `PlannerAddress.read` (`screen/lib/src/http/planner_source.dart`) checks the runtime directory and `porelo-planner`
  as directories and `planner.json` as a regular file, each not a symbolic link, owned by this account's uid and
  with no bit for group or others; every directory above the runtime directory belongs to root or this account, and
  one others may write is sticky, as `/tmp` is, so no other account can rename a step between the checks and the
  read; a `.` or `..` in the runtime directory is refused, since a trailing `/.` makes `stat` follow a link. Owners
  and modes are read with one `/usr/bin/stat` under `LC_ALL=C`, since Dart's `FileStat` has no owner; an unknown
  uid or an answer `stat` does not give refuses.
- **Red first:** `planning_board_test.dart` - a refusal's description shown; a board that failed loads again on the
  provider's change. `planner_source_test.dart` - a runtime directory or `porelo-planner` open to others, a symbolic
  link in place of either, files owned by another uid, and a pipe in place of `planner.json`, whose read would wait
  for ever, are refused; the existing address tests make their directories owner-only, as the planner does.
- A refusal's description comes after the parameters' own words, since a provider's generated description names the
  refusal and its raw parameters.
- **Code reading:** read by another model; its findings are fixed in this plan, each with a test seen red first: the
  directories above the runtime directory, `.` and `..`, the description's place and its control characters, a live
  board with workflow faults loaded again on every change, and tests for a linked `planner.json`, group-only and
  other-only modes, an unknown uid with a runtime directory, and the owners of `porelo-planner` and `planner.json`.

## Acceptance

- The red-first tests seen red, then green; `./mvnw -B -s settings.xml -Pall -pl screen verify` green.
