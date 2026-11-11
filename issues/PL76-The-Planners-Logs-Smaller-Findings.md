# PL76 — The Planner's Log's Smaller Findings

```json
{
  "$schema": "issue-1.json",
  "id": "PL76",
  "title": "The Planner's Log's Smaller Findings",
  "state": "Idea",
  "size": "-",
  "priority": "Medium",
  "rank": 1,
  "blockedBy": [],
  "covers": "A log file Logback opens again after a fault is the account's alone too, a state directory with a pattern character rolls the log over by its real name, and a store with many faults keeps the log's history"
}
```

**What must be true.** Every file of the planner's log is the account's alone however it was opened, the log rolls
over under its own name wherever it lies, and one store's faults do not push the rest of the log out.

Found in the reading of the code that made the planner keep a log:

- `LogFile.PrivateAppender` makes a file owner-only only when Logback opens it through `openFile`; Logback's recovery
  after a write fault opens the file again by name, with the umask's mode. The `logs/` directory, the account's
  alone, still keeps others out.
- `LogFile.open` passes the log directory into Logback's file-name pattern, where `%` and `(` are conversion words: a
  state directory holding them rolls the log over to another name, or the start fails.
- `BoardLog` writes one line per fault for each new revision: a repository with thousands of broken files fills the
  log's 40 MB with them and pushes everything else out. A cap per revision with one line for the rest would keep it.
- `GitRunner.scrubbed` takes out credentials by their shape (`user:password@`, query strings, known token prefixes,
  `Authorization:`); a bare secret a credential helper prints without such a shape is not recognized, and the log is
  now a second place the scrubbed text reaches.
