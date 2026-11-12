# PL33 — The Planner As A Service Of The System

```json
{
  "$schema": "issue-1.json",
  "id": "PL33",
  "title": "The Planner As A Service Of The System",
  "state": "Backlog",
  "size": "L",
  "priority": "High",
  "rank": 20,
  "blockedBy": [
    "PL29"
  ],
  "covers": "The planner runs as something the system knows - a systemd user service on Linux, a task started at sign-in on Windows - started at sign-in by the package, stopped and seen with the system's own commands, instead of a process the window leaves running unseen"
}
```

**Size:** L - two platforms and one contract with the window, the unit's and the task's name; with Windows a task
started at sign-in in the MSIX manifest instead of a service, the Windows part shrinks to a manifest entry and its
check, so the whole is one issue of L, no longer two.

**Estimate:** 113 min, the measured median of L. Uncertain: whether the person's Git login reaches a user service on
the desktops the packages target, and how an MSIX startup task behaves before the app was first launched.

**What must be true.** The person always knows whether the planner runs and can start and stop it with the system's
own commands: on Linux as a systemd user service of the account (`systemctl --user start|stop|status
porelo-planner`), on Windows as a task started at sign-in, seen and ended in the Task Manager's startup apps; no
Windows service. The package starts it at sign-in. The window keeps its own start of the planner only where no such
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
PATH as `porelo-planner` and starts it detached when no address answers. There is no Windows package yet; the Windows
executable is built and shipped by [PL29](README.md#pl29).

Steps, Linux first, since it waits for nothing:

1. **Linux, a systemd user service of the account** (`planner/app/pom.xml`, a new unit file beside the packages'
   other inputs). The packages ship `/usr/lib/systemd/user/porelo-planner.service`:
   `ExecStart=/usr/libexec/porelo/planner`, `Restart=on-failure`, no root, the account's own environment, Git login
   and runtime directory, `WantedBy=default.target`. The package enables it for every account at sign-in (a user
   preset, or `systemctl --global enable` in the package's install script), never starting it for an account from
   the install itself. The planner stops on SIGTERM within the unit's stop timeout and leaves no address file that
   answers. Red first, integration in the package build: the built `.deb` and `.rpm` hold the unit at that path,
   naming the installed executable, enabled for `default.target`, and `systemd-analyze verify` accepts it; unit in
   `app`: a stop by SIGTERM removes the address file.
2. **Windows, a task started at sign-in** (the MSIX manifest of the Windows package, once it is built). The MSIX
   declares the planner executable as a startup task (`windows.startupTask`), enabled, which needs no restricted
   capability and runs in the person's session with the address file and the owner-only checks the Windows planner
   already has; no Windows service. The ZIP registers nothing. Red first, integration in the package build: the
   built MSIX's manifest names the startup task with the installed executable.
3. **The contract with the window.** The unit's name `porelo-planner.service` and the startup task's id are what the
   window looks for: where one is installed, the window starts or asks it and never a detached process of its own;
   where none is, as for the ZIP, it keeps its own start. Agreed in the channel and changed in the window's
   repository.

## Acceptance

- On Linux, after installing the package and signing in again: `systemctl --user status porelo-planner` shows the
  planner running, `systemctl --user stop porelo-planner` ends it, and the window then says the planner is not
  running instead of starting a hidden one.
- On Windows, after installing the MSIX and signing in again: the planner runs and is listed among the Task Manager's
  startup apps; from the ZIP, the window starts the planner itself as today.

- Whether the person's Git login reaches a user service: `SSH_AUTH_SOCK` and a credential helper's display are set in
  a graphical session's environment but not always in the user manager's; measured on the desktops the packages
  target.
- Whether `AddressFile` removes `planner.json` and its lock on a SIGTERM today, read and measured on the JVM.
- Whether an MSIX startup task of a desktop app runs at the next sign-in before the app was launched once, and how a
  person ends it and turns it off, measured on a Windows machine.
- Whether a user preset or `systemctl --global enable` is the one the `.deb` and `.rpm` both honor, measured on the
  distributions the packages target.
