# PL87 — The Planner Reads Private Repositories With Porelo's Login

```json
{
  "$schema": "issue-1.json",
  "id": "PL87",
  "title": "The Planner Reads Private Repositories With Porelo's Login",
  "state": "Backlog",
  "size": "M",
  "priority": "High",
  "rank": 1,
  "blockedBy": [],
  "covers": "A planner Porelo starts reads and writes a project's private repositories with the login Porelo's vault holds, without a credential helper the person sets up by hand, the token never on disk or a command line"
}
```

**Size:** M - the Git store's calls and its tests, and a contract with the window and with Porelo, which each change
their own side.

**Estimate:** 67 min, the measured median of M.

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

- **The contract, agreed with the window and with Porelo.**
  - The window resolves Porelo's executable once at its start, as an absolute path with its links followed, and
    hands it to the planner it starts in the environment variable `PORELO_EXECUTABLE`. The planner refuses it, and
    lends no login, when the file or its directory may be written by another account.
  - For every https address of the project's centrals, each Git call of the planner carries
    `-c credential.<address>.helper=!<porelo path> vault credential --for <address>`, after the person's own helpers.
    Porelo finds the vault entry for the address itself and answers only in Git's credential protocol on Git's
    pipe. No token, entry or forge mapping passes through the planner.
  - Porelo answers https with a token only, an ssh address with nothing, compares hosts exactly (ASCII, lower
    case), and fails with a message when its vault is shut, never prompting. The planner reports such a failure as
    `CredentialRefused`, as today.
  - Without `PORELO_EXECUTABLE`, nothing changes: the person's own Git configuration is the only helper.
- **The change.** `GitCall` takes the helper line when the variable is set and its path passes the check; the
  address is quoted for the shell Git runs the helper in. A `GIT_CONFIG_*` variable of the environment still never
  reaches Git.
- **Tests, red first.**
  - Unit, `GitCallTest`: with a valid path, a call's line carries the helper for the central's address and no token;
    with a path others may write, or a relative one, it carries none and says why; without the variable, the line is
    as today; a `GIT_CONFIG_*` variable still never reaches Git. Harm: a login lent through a program another account
    placed, or a token on a command line.
  - Integration, over the store's local HTTPS test server: a private repository is read with a stand-in helper at
    the path that answers in Git's protocol, and refused without it.
- **Not before** Porelo's `vault credential --for` and the window's passing of the path are published; until then the
  planner's side changes nothing without the variable.

## Acceptance

- The tests above, each seen red first; on a local VM, a planner the window starts reads a private project without a
  Git configuration of the person's.
