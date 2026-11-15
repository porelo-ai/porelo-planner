# PL159 — The Result Linking's Smaller Findings

```json
{
  "$schema": "issue-1.json",
  "id": "PL159",
  "title": "The Result Linking's Smaller Findings",
  "state": "Idea",
  "size": "-",
  "priority": "Medium",
  "rank": 1,
  "blockedBy": [],
  "covers": "Linking a saved result's issue numbers takes time linear in its line, reads the repository's index only as a plain file inside the checkout and says when it could not, and agrees with the check where a fence was left open above the result"
}
```

**What must be true.** Saving a reading's or a stage's result never hangs on a long line, never reads a file outside
the store's checkout, and links exactly what the store's check then asks to be linked.

- The pattern for an existing Markdown link runs on every line of a result before it is saved, and on one very long
  line of opening brackets with no closing one it takes time quadratic in the line's length; the check uses the same
  pattern on what is already saved, while the save now runs it on what an agent wrote.
- The issue numbers are read from the index of the checkout as it stood before the save, following a symbolic link
  in its place to a file outside the checkout; only the numbers are used.
- When the index is missing or cannot be read, nothing is linked and the save goes on without a word, so the board's
  check warns later on every bare number.
- A fence left open in the issue file above the result's sections makes the check skip the result's lines, while the
  linker, which reads each field alone, still links in them.
