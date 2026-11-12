# PL80 — The Idea Hand-In's And The Mock Copies' Smaller Findings

```json
{
  "$schema": "issue-1.json",
  "id": "PL80",
  "title": "The Idea Hand-In's And The Mock Copies' Smaller Findings",
  "state": "Idea",
  "size": "-",
  "priority": "Medium",
  "rank": 1,
  "blockedBy": [],
  "covers": "A handed-in idea's offer never stays pending for good, is never saved twice after the record drops or resets it, and its paragraph takes no control character; the screen's test of the mock's answers reads the stream as the planner frames it; the commit check bounds its file listing, says every commit's faults and reads only the repository's own issues"
}
```

**What must be true.** One offer makes one story for as long as the window may send it again, an offer whose save
broke off can be cleared, and the screen's test over the mock's recorded answers reads the board event as the planner
frames it.

- An offer written as pending stays so when the planner stops, or the record cannot be written, between the pending
  write and the done write; every later hand-in of it is refused, with no way to clear it but deleting the record.
- The record drops the oldest of a thousand done offers, and a change of the project's repository or branch starts a
  record without them, so an offer sent again after either saves a second story.
- Pending and partly saved offers are never dropped and count toward the record's 16 MB.
- A store's refusal is answered in the store's words, cut to a fault's bound, which may quote the idea's title or
  gain, though the description says a fault never repeats what was sent.
- An issue's paragraph of what must be true takes control characters and Markdown headings or a `**Status:**` line,
  written into the issue file as they are.
- An offer already saved is checked against the board before its story is looked up, so sending it again after a
  repository left the project answers 422 instead of its story; the 409 the description names is answered for a
  revision that moved twice; a refused hand-in still tells the windows of a change.
- The screen's test frames the board event itself, so a change of the planner's framing goes unseen.
- The mock's answers are recorded where `CI` is unset only through `record`'s own check, which a caller passing
  another environment would skip; recording answers green and checks nothing.
- `check-commits` lists the committed files with a git call that has no timeout and ignores the environment it is
  given, so a git that hangs holds the check until the job's own limit.
- `check-commits` answers a whole range as no answer when one commit's issue file is malformed or a stage record path
  cannot be read, so the other commits' faults go unsaid.
- `check-commits` takes an issue file at any depth below `issues/` as measured, a fixture's among them, though the
  prefixes are read only from the repository's own issues.
