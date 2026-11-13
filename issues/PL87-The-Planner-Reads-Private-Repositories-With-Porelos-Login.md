# PL87 — The Planner Reads Private Repositories With Porelo's Login

```json
{
  "$schema": "issue-1.json",
  "id": "PL87",
  "title": "The Planner Reads Private Repositories With Porelo's Login",
  "state": "Review",
  "size": "L",
  "priority": "High",
  "rank": 1,
  "blockedBy": [],
  "covers": "A planner Porelo starts reads and writes a project's private repositories with the login Porelo's vault holds, without a credential helper the person sets up by hand, the token never on disk or a command line"
}
```

**Size:** L - the Git store's calls, a check of Porelo's executable and every directory above it on Linux and Windows,
and their tests; and a contract with the window and with Porelo, which each change their own side. It grew from M with
the reading of its plan: the helper's isolation, the executable's ancestors and the request check.

**Estimate:** 113 min, the measured median of L.

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

What else stands today, read in the code: every call starts with `GitCall.SAFE_CONFIG` (`GitCall.java:27`):
`protocol.allow=never` with only `https` and `ssh` allowed, `credential.interactive=never`, no hooks; the person's
global and system configuration still apply. `CloneUrl` (`CloneUrl.java:11`) and `stories/Central` take only
`https://host[:port]/path`, `ssh://` and `user@host:path`, without a password or `..`. `PosixPrivateFiles`
(`requireNoWayRound`, `PosixPrivateFiles.java:219`) and `WindowsAccess.requireSafeAncestor` (`WindowsAccess.java:251`)
already refuse a path reached through a link or junction, or under a directory another account can rename, delete,
re-permission or take over. `GitRunner` puts Git's standard error, scrubbed by fixed patterns (`GitRunner.java:51`),
into its failures and logs.

- **The contract, agreed with the window and with Porelo.**
  - The window resolves Porelo's executable once at its start by its own lookup - its own install location, never a
    setting, a search path or anything a repository controls - as an absolute path with its links followed, and sets
    `PORELO_EXECUTABLE` for the planner it starts, overwriting any inherited value. Trusted callers are the window
    and a person starting the planner from their own shell, whose environment is their own; the planner trusts the
    variable no further than the checks below.
  - For every https address of the project's centrals, each Git call of the planner that reaches that central (a
    fetch, a push, an `ls-remote`) carries
    `-c credential.<address>.helper=` (empty, which clears every helper collected before it for that address),
    `-c credential.<address>.helper=!'<porelo path>' vault credential --for '<address>'` and
    `-c credential.<address>.useHttpPath=true`. Porelo's helper is the only one Git asks or tells about that
    address: no earlier helper sees the token on `store` or `erase`, and none can override the login.
  - Porelo answers `get` only, and only when the request names `protocol=https`, exactly the host of `--for` (ASCII,
    lower case), its effective port (443 when none is given) and a path equal to `--for`'s or below it at a `/`; it
    ignores `store` and `erase`. It answers an ssh address and any other request with nothing, writes no token to its
    standard error or anywhere else, and fails with a message when its vault is shut, never prompting. The planner
    reports such a failure as `CredentialRefused`, as today. The Git configuration key alone authorizes nothing.
  - Without `PORELO_EXECUTABLE`, nothing changes: the person's own Git configuration is the only helper.
- **Decided.** With `PORELO_EXECUTABLE`, only Porelo's login applies to the project's https centrals; the person's
  own helpers no longer do for those addresses. A central that redirects is refused until `project.yml` names its new
  address.
- **The executable, checked before any helper is built.** `PORELO_EXECUTABLE` must be absolute, equal to its real
  path (no linked part, links and junctions refused rather than followed), a regular file the account may execute,
  owned by the account, root, SYSTEM, Administrators or TrustedInstaller, and writable by nobody else. Every directory
  above it passes the existing ancestor checks: on Linux owner and mode, a POSIX ACL showing in the group bits as its
  mask; on Windows owner and every allowing entry, inherited ones and groups included, refused for write, delete,
  delete-child, re-permission and take-ownership rights. Since no other account can then change the file or any
  directory above it, nothing can swap it between the check and Git's run. A check that cannot run refuses, and the
  planner lends no login.
- **The helper line.** The path and the address are each one single-quoted argument of the shell Git runs the helper
  in, Git for Windows' too; a `'` in either refuses the helper. The address is the central as `CloneUrl` and `Central`
  already checked it, before the line is built. The helper is never written into a clone's configuration.
- **Redirects and rewrites.** Calls that carry the helper add `-c http.followRedirects=false`, so no request, the later
  authenticated ones included, follows a redirect to another host, port or scheme; a central that redirects (a
  renamed repository) is refused and its address must be corrected in `project.yml`. `http` stays refused by
  `protocol.allow=never`. An `insteadOf` or `pushInsteadOf` rewrite of the person's configuration is applied before
  Git asks a helper, so a rewritten https request reaches Porelo's helper only under the rewritten address, which
  Porelo's own check of host, port and path refuses; a rewrite to ssh asks no helper at all.
- **SSH.** An `ssh://` or `user@host:path` central gets no helper line; Git asks no credential helper over ssh.
- **Errors and tracing.** A call carrying the helper also switches off every trace2 target, by `GIT_TRACE2=0`,
  `GIT_TRACE2_EVENT=0` and `GIT_TRACE2_PERF=0` in its environment: Git reads `trace2.normalTarget`,
  `trace2.eventTarget` and `trace2.perfTarget` before any `-c`, so an empty `-c` leaves a target of the person's
  configuration writing (measured with Git 2.53), while the variables override it; `GIT_TRACE*` and `GIT_CURL_VERBOSE` are already dropped with every other `GIT_*` variable
  (`GitCall.java:128`). The token passes only through Git's pipe to the helper: no temporary file, no command line,
  no stored helper. Since `GitRunner`'s patterns cannot know an arbitrary token, a failed call that carried the helper
  reports only its kind and Git's scrubbed words, and Porelo's agreement above keeps the token out of the helper's
  diagnostics, which Git passes on to its standard error.
- **Tests, red first.**
  - Unit, `GitCallTest`: with a valid path, a call's line carries the clearing entry, the quoted helper and
    `useHttpPath` for the central's address, `http.followRedirects=false` and the cleared trace targets, and no
    token; an ssh central gets none; a `'` in the path refuses; without the variable the line is as today; a
    `GIT_CONFIG_*` variable still never reaches Git. Harm: a login lent to another helper, host or program, or a token
    on a command line.
  - Unit, the executable's check over fakes of both systems: a relative path, a link on the way, a file or an
    ancestor another account may write, delete or take over, a Windows entry inherited from above, and a check that
    cannot run each refuse. Harm: a login lent through a program another account placed.
  - Integration, over the store's local HTTPS test server: a private repository is read with a stand-in helper that
    answers in Git's protocol; a plaintext `store` helper of the person's configuration holds nothing afterwards; a
    redirect to another port gets no credential; without the helper the read is refused.
- **Not before** Porelo's `vault credential --for` and the window's passing of the path are published; until then the
  planner's side changes nothing without the variable.

## Acceptance

- The tests above, each seen red first; on a local VM, a planner the window starts reads a private project without a
  Git configuration of the person's.

## Not taken

- **A test with a second repository on the same host.** `useHttpPath` is what keeps the login to the one repository
  the call names, and the integration test that reads the private repository already goes red without it, since the
  helper then gets no path. A second repository adds no harm that test does not already show.
- **A rule for a Porelo installed under `C:\Program Files`.** The check of every directory above the program refuses
  one where TrustedInstaller may rename or re-permission, as Windows grants it there. The window sets no
  `PORELO_EXECUTABLE` on Windows, since Porelo has no Windows build, so no such path reaches the check; a Windows build
  of Porelo brings its own change.
