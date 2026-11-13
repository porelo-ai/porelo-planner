# PL33 — The Planner As A Service Of The System

```json
{
  "$schema": "issue-1.json",
  "id": "PL33",
  "title": "The Planner As A Service Of The System",
  "state": "Backlog",
  "size": "L",
  "priority": "Low",
  "rank": 20,
  "blockedBy": [],
  "covers": "On Linux the planner runs as a systemd user service the deb and rpm packages install, started at sign-in, stopped and seen with the system's own commands, instead of a process the window leaves running unseen"
}
```

**Size:** L - the user unit with its start limits, the deb and rpm install scripts, an exit code of its own for a
refused start, the package checks, a VM measurement on the target desktops, and one contract with the window. The
Windows startup task is split off into [PL111](README.md#pl111).

**Estimate:** 113 min, the measured median of L. Uncertain: whether the person's Git login reaches a user service on
the desktops the packages target.

**What must be true.** The person always knows whether the planner runs and can start and stop it with the system's
own commands: on Linux as a systemd user service of the account (`systemctl --user start|stop|status
porelo-planner`). The package starts it at sign-in. The window keeps its own start of the planner only where no such
service or task is installed, as for the ZIP, and nothing of the planner keeps running that the person cannot see or
end.

## Why

Today the window starts the planner as a detached process and it runs until the person signs out. That keeps it
shared by every window and tool of the account, but it is a background process the person never started knowingly,
does not see and has no command to stop. A service of the system is the usual way to run a long-lived process: it is
listed, logged, started and stopped where every other service is.

## Plan

Read in today's code: the `.deb` and the `.rpm` (`planner/app/pom.xml`, jdeb and the rpm plugin) install the native
executable as `/usr/libexec/porelo/planner` and nothing else to start it. The planner takes one lock per account and
writes `porelo-planner/planner.json` under `XDG_RUNTIME_DIR` (`AddressFile`); the window finds the executable on the
PATH as `porelo-planner` and starts it detached when no address answers.

Steps:

1. **Linux, a systemd user service of the account** (`planner/app/pom.xml`, a new unit file beside the packages'
   other inputs). The packages ship `/usr/lib/systemd/user/porelo-planner.service`:
   `ExecStart=/usr/libexec/porelo/planner`, `Restart=on-failure`, no root, the account's own environment, Git login
   and runtime directory, `WantedBy=default.target`. The package enables it for every account at sign-in (a user
   preset, or `systemctl --global enable` in the package's install script), never starting it for an account from
   the install itself. The planner stops on SIGTERM within the unit's stop timeout and leaves no address file that
   answers. Red first, integration in the package build: the built `.deb` and `.rpm` hold the unit at that path,
   naming the installed executable, enabled for `default.target`, and `systemd-analyze verify` accepts it; unit in
   `app`: a stop by SIGTERM removes the address file.
2. **The contract with the window.** The unit's name `porelo-planner.service` is what the window looks for: where
   it is installed, the window starts or asks it and never a detached process of its own;
   where none is, as for the ZIP, it keeps its own start. Agreed in the channel and changed in the window's
   repository.

The plan's reading by another model, each point checked in the code where it rests on it, asks the plan to state:

- **Consent and opt-out.** A global enable runs the planner at every account's sign-in, and a user's
  `systemctl --user disable` does not undo a global enable; only `systemctl --user mask porelo-planner` does. The
  person's opt-out is that mask, documented, and an upgrade keeps it.
- **What the package scripts may do.** Only fixed package-owned paths and named service-manager calls:
  `deb-systemd-helper --user enable|purge` and `deb-systemd-invoke --user restart|stop` on Debian, which act on running
  user managers through `systemctl --user --machine <uid>@`, and the systemd user macros on the `.rpm`. No script
  sources a profile, runs a user's Git or credential helper, reads a token, or touches a runtime directory.
- **Upgrade and removal.** An upgrade restarts the unit in every running user manager, so no planner of the older
  install serves a newer window; a removal stops it there and removes the global enable while the unit still exists.
- **No restart loop.** `AddressFile.open` refuses a second planner (`alreadyRuns`); under `Restart=on-failure` that
  refusal would restart forever. The planner ends a refused start with an exit code of its own, which the unit names
  in `RestartPreventExitStatus=`, and the unit bounds restarts with `StartLimitIntervalSec=`/`StartLimitBurst=`.
- **The lock stays.** No cleanup unlinks `planner.lock` or replaces its directory, and the unit sets no
  `RuntimeDirectory=`, whose cleanup would; `AddressFile.close` releases the lock and leaves the file, as now.
- **The Git login.** The window imports no environment into the user manager wholesale; only `SSH_AUTH_SOCK` and the
  display variables a credential helper needs, and never a credential's value.

**The contract with the window, Linux**, agreed in the channel:

- The unit is `porelo-planner.service`, a user unit at `/usr/lib/systemd/user/porelo-planner.service`.
- Where that file exists and `systemctl --user` answers, the window never starts a planner of its own: it starts the
  unit with `systemctl --user start porelo-planner.service` when no address answers, sees it by the address file
  beside `systemctl --user is-active porelo-planner.service`, and never stops it; at its own end it leaves the unit
  running, as it leaves any planner it found. Stopping the unit belongs to the person.
- Where the file is missing, or `systemctl --user` fails (no user bus, as in an ssh or container session), the window
  starts the planner itself as today, with `--end-with-input`, and logs why.
- The window imports into the user manager only `SSH_AUTH_SOCK` and the display variables a credential helper needs,
  each only when set; the unit needs nothing else from the window's environment. The planner's output goes to the
  journal, and the window's log names `journalctl --user -u porelo-planner`.
- The window restarts the unit only when `/health` names a version, the installed package's version is known and
  differs, and no save of the person's is outstanding.
- A refused start, another planner already holding the lock, ends with an exit code of its own, named in the unit's
  `RestartPreventExitStatus=`.

## Acceptance

- On Linux, after installing the package and signing in again: `systemctl --user status porelo-planner` shows the
  planner running, `systemctl --user stop porelo-planner` ends it, and the window then says the planner is not
  running instead of starting a hidden one.

## Open questions

- Does the package enable the start at sign-in globally for every account, with `systemctl --user mask` as the
  opt-out, or does it stay off until the person or the window enables it per account?

## To be checked

- How the window reads the installed package's version to compare with `/health`: a file the package writes beside
  the executable, or the executable's own answer; agreed with the window before it is built.
- Whether the person's Git login reaches a user service: `SSH_AUTH_SOCK` and a credential helper's display are set in
  a graphical session's environment but not always in the user manager's; measured on the desktops the packages
  target.
- Whether `AddressFile` removes `planner.json` on a SIGTERM today: read, it does, through the context's close, and
  `NativeStartIT` already sees the file gone after a stop; the lock file stays by design.
- Whether a user preset or `systemctl --global enable` is the one the `.deb` and `.rpm` both honor, measured on the
  distributions the packages target.
