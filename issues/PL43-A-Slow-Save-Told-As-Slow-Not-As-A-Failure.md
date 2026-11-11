# PL43 — A Slow Save Told As Slow, Not As A Failure

```json
{
  "$schema": "issue-1.json",
  "id": "PL43",
  "title": "A Slow Save Told As Slow, Not As A Failure",
  "state": "Idea",
  "size": "S",
  "priority": "Medium",
  "rank": 26,
  "blockedBy": [],
  "covers": "A save that takes longer than the screen waits is shown as still saving and, once the board shows it done, as saved; two messages never run together"
}
```

**Size:** S - the screen package alone: the save's wait, its message and the joining of messages.

**What must be true.** A person who moves a story or an issue sees it being saved while the push takes its time, and sees it saved once the board shows the move; a slow push is never told as a failure, and each message stands apart.

## Plan

- Today a save waits 30 s; a push that takes longer is told as "the planner did not answer within 30 s", and the board that then arrives over the stream with the move done is told as "moved ... elsewhere", both run together in one line.
- The screen keeps a pending save as saving past the wait, takes a board that shows the saved move as the save's answer, and shows each message on a line of its own.
- Red first: a widget test whose fake answers the save after the wait, with the board showing the move, shows it saved and no failure.

## Acceptance

- A save answered late shows as saving, then saved; no message says the planner did not answer, and no message runs into another.
