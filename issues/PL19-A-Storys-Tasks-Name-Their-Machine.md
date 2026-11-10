# PL19 — A Story's Tasks Name Their Machine

```json
{
  "$schema": "issue-1.json",
  "id": "PL19",
  "title": "A Story's Tasks Name Their Machine",
  "state": "Backlog",
  "size": "-",
  "priority": "Medium",
  "rank": 10,
  "blockedBy": ["PL09"],
  "covers": "The window's report of a task it started names the machine it runs on, and a story's work answers it per task, so a task opened from the board opens on its machine"
}
```

**What must be true.** A task opened from the planning board opens through the machine it runs on: the window names
that machine when it reports a task it started, the planner keeps it with the task, and a story's work answers it
beside each task. The planner reaches no machine itself; it only tells back what the window reported.
The name is the one the person sees, 1 to 128 characters without control characters or line breaks, kept and
answered back verbatim, never parsed and never put into a path, a file name or a URL.
