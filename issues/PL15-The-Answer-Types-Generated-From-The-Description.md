# PL15 — The Answer Types Generated From The Description

```json
{
  "$schema": "issue-1.json",
  "id": "PL15",
  "title": "The Answer Types Generated From The Description",
  "state": "Idea",
  "size": "-",
  "priority": "Low",
  "rank": 3,
  "blockedBy": [],
  "covers": "The planner's answer records, and possibly its controller interfaces, generated from openapi/planner.yaml instead of written by hand"
}
```

**What must be true.** The planner's Java answer types are generated from `openapi/planner.yaml` at build time, so a
change of the description changes the code with it and the compiler, not only a test, holds the controllers to it;
they stay records with `@Nullable` where the description allows null, and run in the native executable.

## To be checked

- openapi-generator 7.27.0 writes records (`useRecords`); the overridden templates that make them null-safe come as
  a published Maven artifact of the repository that already keeps them, never as a copy. Its coordinates, once
  published.
