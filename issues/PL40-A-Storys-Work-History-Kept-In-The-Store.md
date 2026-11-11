# PL40 — A Story's Work History Kept In The Store

```json
{
  "$schema": "issue-1.json",
  "id": "PL40",
  "title": "A Story's Work History Kept In The Store",
  "state": "Idea",
  "size": "-",
  "priority": "High",
  "rank": 27,
  "blockedBy": [],
  "covers": "A story's runs - order, tasks, each issue's progress, the reading rounds spent and the tasks' outcomes - are kept by the store's adapter beside the story, so the history survives another machine, a reinstall and reaches everyone who plans the project"
}
```

**What must be true.** A story's work history - each run's order, the tasks it started, each issue's progress, the
reading rounds spent against the limit and how each task ended - is kept by the store's adapter beside the story,
like its states. It survives a move of the story, another computer, a reinstall, and everyone who plans the project
sees the same history.

## Why

The planner keeps a story's runs in a local record of the account that ran them, under the state directory of that
computer. A move does not end a run, but the history is lost with the computer or a fresh account, and nobody else
planning the project sees it. Only the adapter knows how its store keeps such a record: the Git store would commit
it like a state change; another adapter would use its own place.

## Open questions

- Where the Git store keeps it: a file per story beside the stories index in the project's repository, or a branch of
  its own, out of the way of the issues' history?
- What of a task's outcome is kept: its answer is an agent's text, which a commit makes visible to everyone with read
  access to the store.

## To be checked

- What of the local record must stay local, such as a task's container and machine, and what is history.
- How a run in flight is kept consistent when two planners of the same project write history.
