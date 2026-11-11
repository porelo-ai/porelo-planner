# PL31 — A Short Event Id For Any Number Of Repositories

```json
{
  "$schema": "issue-1.json",
  "id": "PL31",
  "title": "A Short Event Id For Any Number Of Repositories",
  "state": "Review",
  "size": "S",
  "priority": "High",
  "rank": 2,
  "blockedBy": [],
  "covers": "The Git store's revision is a fixed-length digest of its repositories' commits, at most 200 characters as the screen accepts, so a project with many repositories keeps its live board"
}
```

**Size:** S - one store method, the description's words and a test on each side.

**What must be true.** A project's board streams to the window however many repositories its `project.yml` names.
The Git store's revision was one `<repository>@<commit>` per repository joined by spaces. The window sends the
revision back as the `Last-Event-ID` header and checks both the event's `id` and the board's `revision` against 200
printable characters without spaces, so with a dozen repositories it ended the watch at once and the planning page
offered only a retry.

## Plan

- **The planner.** `GitStore` gives as its revision the SHA-256 of that text, as 64 lower-case hexadecimal
  characters. Equal commits give equal revisions and any moved commit another. Every use of a revision - the event's
  `id`, saves, readings, the stale check - compares it only for equality, so nothing else changes.
- **The contract.** The description's `Revision` says it is opaque, at most 200 printable characters without spaces,
  and the Git store's is that digest; the `board` event's `id` is the revision.
- **The screen.** Its bound of 200 characters stays, now stated in the description.
- **Tests, red first.**
  - A unit test gives the revision of twenty repositories. It must match the bound and be 64 hexadecimal
    characters, equal for equal commits and different for others; before, it was about 1,200 characters.
  - The screen's test refusing 201 characters stays. A new one follows a revision of exactly 200 characters.

## Acceptance

- The unit test is seen red on the old code and green after the change.
- A board with twenty repositories reaches the screen's watch.
