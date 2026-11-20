# PL125 — Porelo's Login Lent Once Porelo Answers Git

```json
{
  "$schema": "issue-1.json",
  "id": "PL125",
  "title": "Porelo's Login Lent Once Porelo Answers Git",
  "state": "Implementing",
  "size": "M",
  "priority": "High",
  "rank": 1,
  "blockedBy": [],
  "covers": "A planner Porelo starts reads and writes a project's private https repositories with the login Porelo's vault holds, now that Porelo's credential command answers Git, without a Git configuration the person writes by hand, and hands the same token to the GitHub store's API calls through the helper alone"
}
```

**Why this size.** M: the switch itself is one line in `LentLogin`, but the token for the GitHub store's API calls
is a new call of the helper with its bounds and refusals, and the issue is judged by a run on a local VM.

**What must be true.** A person whose project lives in private https repositories opens the planning screen and sees
the board without writing a Git configuration first: the planner the window started reaches those repositories with
the login Porelo's vault holds for their forge, the token never on disk, in a file name, a command line or a log.

Today the Git store builds the lent login - the check of the executable `PORELO_EXECUTABLE` names and of every
directory above it, the helper line with `useHttpPath`, no redirects and no trace targets - but `LentLogin.of` lends
none, whatever the variable names, since Porelo's `vault credential --for <address>` is not yet published; the
person's own Git configuration applies as before. Switching it on means `LentLogin.of` taking the checked executable,
and a run on a local VM where a planner the window started reads a private project without a Git configuration of
the person's. The contract it builds against is under [A lent login is Porelo's alone](../doc/decisions.md#a-lent-login-is-porelos-alone).

## Plan

- Today `LentLogin.of(environment)` (`store/git`) answers `NONE` whatever the variable names; its three callers,
  `GitProjects` (line 103), `GitRemote` and `GitLocalRefs`, pass that on to every Git call. `LentLogin.of(environment,
  check)` already checks the executable and `config(url)` already writes the helper line. Porelo's `vault credential
  --for <address>` is published now.
- Switch: `LentLogin.of(environment)` returns `of(environment, PoreloExecutable.of(environment, WINDOWS))`; the
  callers stay as they are.
- The token for the API: `LentLogin.token(address)` runs the checked executable as Git would run the helper,
  `vault credential --for <address> get`, writes the credential request (`protocol`, `host`, `path`) on its
  standard input and reads `password=` from its standard output, bounded in size and time, without a shell; the
  token is never logged or kept beyond the call. No lent login, no answer or a refusal is
  `StoreException.CredentialRefused`, naming the address. A public `ForgeTokens` in `store/git` hands it out, so the
  app can give the GitHub store its `TokenSource` without the store knowing Git.
- Red first: `GitCallTest`'s check that a set variable still lends nothing turns into one that it lends Porelo's
  helper; `LentLoginTest` for `token` against a stand-in executable that answers the credential protocol, one that
  answers nothing, and one that writes more than the bound.

## Acceptance

- On a local VM, a planner started with `PORELO_EXECUTABLE` set reads and writes a private https project with no
  Git configuration of the person's, the token in Porelo's vault alone; the run's log and the process list show no
  token.
- `ForgeTokens` answers the same token for the organization's address that Git gets through the helper.

## Order and files

Built in this order, each pushed once what it needs is published: [PL164](README.md#pl164)'s types;
[PL169](README.md#pl169)'s contract (independent, at once); then [PL165](README.md#pl165), [PL125](README.md#pl125)
followed by [PL166](README.md#pl166), and [PL169](README.md#pl169)'s behavior followed by [PL167](README.md#pl167),
side by side; then [PL168](README.md#pl168). Each builds against the forge clients' published surface with fakes of
its own.

- Owns `store/git`'s `LentLogin` and a new `ForgeTokens`, with `LentLoginTest`, `GitCallTest` and
  `ForgeTokensTest`. [PL165](README.md#pl165) wires `ForgeTokens` into `StoreConfiguration`.

## To be checked

- Which VM slot the acceptance run takes, asked of the coordinator before it.
