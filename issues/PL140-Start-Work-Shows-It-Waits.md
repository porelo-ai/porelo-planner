# PL140 — Start Work Shows It Waits

```json
{
  "$schema": "issue-1.json",
  "id": "PL140",
  "title": "Start Work Shows It Waits",
  "state": "Backlog",
  "size": "XS",
  "priority": "Medium",
  "rank": 63,
  "blockedBy": [],
  "covers": "While the Start work dialog waits for the planner's answer or for the window's Start, it shows the screen's loading mark beside the words, and while it waits for Start it says that the window shows how far the start is, instead of grey fields alone"
}
```

**Size:** XS - one dialog of the screen package; no contract.

**What must be true.** A person who presses Start in *Start work* sees that the start is under way and where to watch
it, instead of a dialog whose fields are all grey with no sign that anything happens.

## Plan

- Today `screen/lib/src/ui/story_work_dialog.dart` shows "Waiting for the planner’s answer…" while `work.busy`, and
  under each task not started "Waiting for Start" while `work.starting`; every field and the start button are off
  meanwhile, with no mark. The window's start hook answers its one Future only once the agent runs, after its
  container and image are built, which can take minutes; the window shows that start's steps in its own operation.
- Both lines get the screen's loading mark beside them, the still `Icons.hourglass_empty` the board's waiting line
  uses, since a waiting page must have no endless animation; the task's line while `work.starting` reads "Waiting for
  Start; the window shows how far it is."
- The hook stays one Future: the start's steps are the window's to show, in the operation it opens at once, so the
  screen holds no second copy of them and the contract does not change.
- No test: the dialog only reads wrong while it waits; nothing is started twice or lost.

## Acceptance

- The screen's suite passes; the dialog shows the mark and the words while it waits for the planner and for Start.
