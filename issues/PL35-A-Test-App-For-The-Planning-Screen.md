# PL35 — A Test App For The Planning Screen

```json
{
  "$schema": "issue-1.json",
  "id": "PL35",
  "title": "A Test App For The Planning Screen",
  "state": "Accepted",
  "size": "-",
  "priority": "High",
  "rank": 22,
  "blockedBy": [],
  "covers": "A small Flutter app that embeds only the planning screen against a mock: what the window hands the screen is entered by hand, every call the screen makes toward the window is shown, and the window's events back - a task started, ended or stopped, its answer - are sent by hand to see the board react"
}
```

**What must be true.** The planning screen can be tried alone, without the window, against a mock. Everything the
window normally hands the screen can be entered by hand; every call the screen makes toward the window can be seen
with its arguments; and every event the window would send back can be fired by hand to watch the board react.

- **From the window, entered by hand:** the providers with their projects and connection, the current provider, the
  project chosen elsewhere, the place (story, issue), the agents offered, and how the window answers a start (a task
  and container named, a failure with words, no answer) and the opening of a task (possible, or unavailable with a
  reason).
- **Toward the window, shown:** every call of the screen's hooks - `host.go`, `host.openLink`, `host.openNeedsYou`,
  the Start hook, a task's start reported, a task's stop, a task opened - each with its time and arguments, in a log
  that can be copied and cleared.
- **From the window, fired by hand:** a task's event - started, ended (finished or not, why, the answer, unpushed
  commits) or stopped - with its machine and container; and for the board itself a new revision, a dropped stream
  and a refused save.

## Why

The operator's test of the board in the window mixes the screen's faults with the window's and the planner's, and
needs a real project and a real agent run to see how the board takes a task's end. A test app shows the screen's
own contract alone and makes every reaction reachable at once.

A proposed design is drawn on a canvas; the operator reads it before the analysis.

## Decided

- The data behind the screen can be switched in the app: the in-package fake, which needs no process, or the
  planner's mock mode over HTTP, which shows the real contract.
- The app is a module of its own beside `screen/`, embedding the screen package by path.
