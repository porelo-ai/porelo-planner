# PL33 — The Planner As A Service Of The System

```json
{
  "$schema": "issue-1.json",
  "id": "PL33",
  "title": "The Planner As A Service Of The System",
  "state": "Idea",
  "size": "-",
  "priority": "High",
  "rank": 20,
  "blockedBy": [],
  "covers": "The planner runs as a service the system knows - a systemd user service on Linux, a service on Windows - started, stopped and seen with the system's own commands, instead of a process the window leaves running unseen"
}
```

**What must be true.** The person always knows whether the planner runs and can start and stop it with the system's
own commands: on Linux as a systemd user service (`systemctl --user start|stop|status porelo-planner`), on Windows as
a service (`sc`, the Services panel). The window starts or asks that service instead of a detached process of its own,
and nothing of the planner keeps running that the person cannot see or end.

## Why

Today the window starts the planner as a detached process and it runs until the person signs out. That keeps it
shared by every window and tool of the account, but it is a background process the person never started knowingly,
does not see and has no command to stop. A service of the system is the usual way to run a long-lived process: it is
listed, logged, started and stopped where every other service is.

## Open questions

- Linux: a systemd user service of the account (no root, the account's own Git login and runtime directory) or a
  system service? The address file and the owner-only checks assume the account's own.
- Windows: a per-user service, a service under the account, or a task started at sign-in? A service runs outside the
  person's session by default, and the MSIX would need the restricted capability to install one.
- Started when: enabled at sign-in by the package, or started on demand by the window and stopped after a while
  without any window?
- Is the window's own start of the planner then dropped, or kept for a ZIP without the service?
