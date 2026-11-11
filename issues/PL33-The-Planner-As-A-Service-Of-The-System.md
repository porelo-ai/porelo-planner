# PL33 — The Planner As A Service Of The System

```json
{
  "$schema": "issue-1.json",
  "id": "PL33",
  "title": "The Planner As A Service Of The System",
  "state": "Analyzed",
  "size": "XL",
  "priority": "High",
  "rank": 20,
  "blockedBy": [],
  "covers": "The planner runs as a service the system knows - a systemd user service on Linux, a service on Windows - started, stopped and seen with the system's own commands, instead of a process the window leaves running unseen",
  "openQuestions": 4
}
```

**Size:** XL - two platforms, each crossing into the window's start of the planner; it splits into a Linux issue (L)
and a Windows issue (L) that waits for the Windows executable.

**What must be true.** The person always knows whether the planner runs and can start and stop it with the system's
own commands: on Linux as a systemd user service (`systemctl --user start|stop|status porelo-planner`), on Windows as
a service (`sc`, the Services panel). The window starts or asks that service instead of a detached process of its own,
and nothing of the planner keeps running that the person cannot see or end.

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

The split, each its own issue once the questions below are answered:

1. **Linux, a systemd user service** (L). The packages ship `/usr/lib/systemd/user/porelo-planner.service`:
   `ExecStart=/usr/libexec/porelo/planner`, `Restart=on-failure`, no root, the account's own environment and runtime
   directory; enabled or not as decided below. The planner stops on SIGTERM within the unit's stop timeout and
   leaves no address file that answers. The window asks `systemctl --user` to start the unit where it is installed,
   and keeps its own start only as decided below - a change in the window. Red first, integration in the package
   build: the built `.deb` and `.rpm` hold the unit at that path, naming the installed executable, and
   `systemd-analyze verify` accepts it; unit in `app`: a stop by SIGTERM removes the address file.
2. **Windows** (L, after [PL29](README.md#pl29)). The kind decided below - a service, or a task started at sign-in -
   registered by the MSIX or the ZIP's script, with the address file and the owner-only checks the Windows planner
   already has. Planned in its own issue once the kind is decided, since an MSIX service needs a restricted
   capability.

## Acceptance

- On Linux, after installing the package: `systemctl --user status porelo-planner` shows the planner running once the
  window has asked for it, `systemctl --user stop porelo-planner` ends it, and the window then says the planner is not
  running instead of starting a hidden one.

## Open questions

- Linux: a systemd user service of the account (no root, the account's own Git login and runtime directory) or a
  system service? The address file and the owner-only checks assume the account's own.
- Windows: a per-user service, a service under the account, or a task started at sign-in? A service runs outside the
  person's session by default, and the MSIX would need the restricted capability to install one.
- Started when: enabled at sign-in by the package, or started on demand by the window and stopped after a while
  without any window?
- Is the window's own start of the planner then dropped, or kept for a ZIP without the service?

## To be checked

- Whether the person's Git login reaches a user service: `SSH_AUTH_SOCK` and a credential helper's display are set in
  a graphical session's environment but not always in the user manager's; measured on the desktops the packages
  target.
- Whether `AddressFile` removes `planner.json` and its lock on a SIGTERM today, read and measured on the JVM.
