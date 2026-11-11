# PL26 — The Mock Board Saves Moves

```json
{
  "$schema": "issue-1.json",
  "id": "PL26",
  "title": "The Mock Board Saves Moves",
  "state": "Backlog",
  "size": "-",
  "priority": "Medium",
  "rank": 15,
  "blockedBy": [],
  "covers": "The mock mode saves moves into its in-memory board with a new revision and a change event, answers PartlySaved for one fixed story, and serves a story with sub-issues so a refused move can be reached"
}
```

**What must be true.** The window's planning tests can save moves against the mock mode and see them:
- A save changes the in-memory board, answers a new revision and tells the change to the board's windows. A reset restores the board.
- One fixed story id answers PartlySaved, naming a repository pushed and one not.
- One mock story has sub-issues, so a move of the parent that its sub-issues forbid answers MoveNotAllowed.
