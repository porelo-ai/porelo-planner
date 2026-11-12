# PL87 — The Planner Reads Private Repositories With Porelo's Login

```json
{
  "$schema": "issue-1.json",
  "id": "PL87",
  "title": "The Planner Reads Private Repositories With Porelo's Login",
  "state": "Backlog",
  "size": "S",
  "priority": "Medium",
  "rank": 1,
  "blockedBy": [],
  "covers": "A planner Porelo starts reads and writes a project's private repositories with the login Porelo's vault holds, without a credential helper the person sets up by hand, the token never on disk or a command line"
}
```

**Size:** S - one class of the Git store, `GitCall`, and its tests; how Porelo starts the planner is Porelo's own.

**What must be true.** A person whose project lives in private repositories opens the planning screen and sees the
board: the planner Porelo started reaches those repositories with the login Porelo's vault holds for their forge,
without the person writing a Git configuration first. The token stays in the vault: never on disk, in a file name, a
command line or a log.

Today, read in the code: the planner's Git calls keep only `GIT_CONFIG_GLOBAL`, `GIT_CONFIG_SYSTEM` and
`GIT_CONFIG_NOSYSTEM` of the `GIT_*` variables (`GitCall.environment`), so a helper handed in through
`GIT_CONFIG_COUNT`/`GIT_CONFIG_KEY_n` never reaches Git, and a private repository answers `CredentialRefused`
("needs a Git login that works without a prompt") unless the person's own Git configuration names a helper. A real
run on a local VM needed a configuration file written by hand, naming `porelo vault credential` as the helper.

## Plan

- When Porelo starts the planner and says so in the environment it hands over (Porelo's vault named), every Git call
  of the planner gets Porelo's credential helper as one more `-c` in `GitCall.SAFE_CONFIG`, scoped to the https
  addresses of the project's centrals, after the person's own helpers; without it nothing changes. Nothing the
  person's environment names is run as a helper but Porelo's own executable, taken by path.
- Red first, unit, `GitCallTest`: with Porelo's vault named, a call's line carries the helper for the central's host
  and no token; without it, the line is as today; a `GIT_CONFIG_*` variable of the environment still never reaches Git.
- Integration, over the store's local HTTPS test server: a private repository is read with a helper that answers
  from the environment Porelo would give it, and refused without.

## Acceptance

- The tests above, each seen red first; on a local VM, a planner Porelo starts reads a private project without a Git
  configuration of the person's.

## To be checked

- How Porelo starts the planner and which variable names its vault, and the exact helper command Porelo gives for
  it (`porelo vault credential`), asked of Porelo's agent; whether Porelo's executable path is known to the planner.
