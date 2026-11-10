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

## Open questions

- Whether to generate at all while the only generator that writes records (openapi-generator's `useRecords`) is
  unreleased, and whether the overridden templates that make its records null-safe come as a published artifact of
  the repository that already keeps them, since a copy is not allowed.
