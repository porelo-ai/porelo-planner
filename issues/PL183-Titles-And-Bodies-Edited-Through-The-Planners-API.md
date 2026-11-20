# PL183 — Titles and Bodies Edited Through the Planner's API

```json
{
  "$schema": "issue-1.json",
  "id": "PL183",
  "title": "Titles and Bodies Edited Through the Planner's API",
  "state": "Idea",
  "size": "-",
  "priority": "Highest",
  "rank": 3,
  "blockedBy": ["PL182"],
  "covers": "A story's or an issue's title and named body sections are changed through the planner's API against the revision the caller read, without losing a person's edit made on the tracker meanwhile; built right after the cut-over"
}
```

**What must be true.** A person, or an agent acting for them, corrects a story's or an issue's title and the text of
its named sections through the planner alone, and an edit made on GitHub in the meantime is never overwritten
unseen.
