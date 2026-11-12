# PL84 — The Board Says A Story's Given State

```json
{
  "$schema": "issue-1.json",
  "id": "PL84",
  "title": "The Board Says A Story's Given State",
  "state": "Idea",
  "size": "-",
  "priority": "Medium",
  "rank": 1,
  "blockedBy": [],
  "covers": "The planner's answers say whether a story's state was given to it rather than derived from its issues, and which of its issues do not fit that state, so the board can show both"
}
```

**What must be true.** A window that reads a story from the planner learns whether its state was given to it - on the
board, by a confirmed move, or in its head - or derived from its issues by the automatic rule, the earliest open state
of its issues, and, for a given state, each issue that does not fit it with its own state; a story without a given
state answers as today.

Read in today's code: the core already keeps a story's given state (`Story.stateGiven`) and takes a confirmed move
against its issues (`Moves`, `StoryStateMismatch` without confirmation), but the board's story answer in
`planner.yaml` carries neither the given state nor the issues that do not fit, so the window cannot show them.
