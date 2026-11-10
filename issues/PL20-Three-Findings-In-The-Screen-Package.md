# PL20 — Three Findings In The Screen Package

```json
{
  "$schema": "issue-1.json",
  "id": "PL20",
  "title": "Three Findings In The Screen Package",
  "state": "Implementing",
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
  blank, before the parameters' `detail`, `message` or `reason`.
- `PlanningBoards._sync` loads a board again through `changeProject` when it holds a problem.
- `PlannerAddress.read` (`screen/lib/src/http/planner_source.dart`) checks the runtime directory and `porelo-planner`
  as directories and `planner.json` as a regular file, each not a symbolic link, owned by this account's uid and
  with no bit for group or others, read with `/usr/bin/stat` under `LC_ALL=C`, since Dart's `FileStat` has no owner;
  an unknown uid or an answer `stat` does not give refuses.
- **Red first:** `planning_board_test.dart` - a refusal's description shown; a board that failed loads again on the
  provider's change. `planner_source_test.dart` - a runtime directory or `porelo-planner` open to others, a symbolic
  link in place of either, and files owned by another uid are refused; the existing address tests make their
  directories owner-only, as the planner does.

## Acceptance

- The red-first tests seen red, then green; `./mvnw -B -s settings.xml -Pall -pl screen verify` green.
