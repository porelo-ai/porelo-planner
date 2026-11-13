# PL111 — The Planner Started At Sign-In On Windows

```json
{
  "$schema": "issue-1.json",
  "id": "PL111",
  "title": "The Planner Started At Sign-In On Windows",
  "state": "Backlog",
  "size": "M",
  "priority": "Low",
  "rank": 47,
  "blockedBy": [
    "PL29"
  ],
  "covers": "On Windows the MSIX package starts the planner at sign-in as a startup task the person sees and switches in the Task Manager, with its own way to stop and see a running planner, instead of a process the window leaves running unseen"
}
```

**Size:** M - a startup task in the MSIX manifest and its package check, a way to stop and see a running planner, and
one contract with the window, measured on a Windows machine.

**Estimate:** 67 min, the measured median of M. Uncertain: how an MSIX startup task behaves before the app was first
launched.

**What must be true.** On Windows the person knows whether the planner runs and can start and end it: the MSIX
starts it at sign-in as a startup task listed in the Task Manager's startup apps, and a running planner can be seen
and stopped. There is no Windows service. The ZIP registers nothing. When no planner runs, the window starts none itself; it
shows that the planner service is not running and offers to start it.

## Plan

- **Today, read in the code.** The Windows executable and its MSIX are built and shipped by
  [PL29](README.md#pl29); the window finds the executable through its alias and starts it detached when no address
  answers.
- **The startup task.** The MSIX manifest declares the planner executable as a startup task
  (`windows.startupTask`), enabled, which needs no restricted capability and runs in the person's session with the
  address file and the owner-only checks the Windows planner already has. Red first, integration in the package
  build: the built MSIX's manifest names the startup task with the installed executable.
- **Stopping and seeing it.** A startup task's switch in the Task Manager turns the start at sign-in on or off; it
  neither stops nor shows a running planner. Stopping and seeing it need their own way, and "start at sign-in off"
  is never shown as "stopped". The window cannot query a startup task of another MSIX package.
- **No silent start.** The window never starts the planner on its own, from the MSIX or the ZIP: when no planner
  answers, its planner page shows a placeholder saying the planner service is not running, with a way to start the
  service and then the planner window.
- **The contract with the window**, agreed in the channel before it is built: the placeholder page, and how the window
  sees and ends a planner the startup task started.

## Acceptance

- After installing the MSIX and signing in again, the planner runs and is listed among the Task Manager's startup
  apps; with none running, from the MSIX or the ZIP, the window starts none itself and shows the placeholder with its
  way to start the service.

## To be checked

- Whether an MSIX startup task of a desktop app runs at the next sign-in before the app was launched once, and how a
  person ends it and turns it off, measured on a Windows machine.
