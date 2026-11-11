# PL15 — The Answer Types Generated From The Description

```json
{
  "$schema": "issue-1.json",
  "id": "PL15",
  "title": "The Answer Types Generated From The Description",
  "state": "Accepted",
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

## Plan

- The record templates come as `org.fuin.porelo:porelo-forge-client-openapi-templates`, a dependency of
  `openapi-generator-maven-plugin` 7.27.0 with `templateResourcePath` `org/fuin/porelo/forge/openapi/templates` (no
  leading or trailing slash), `useRecords` and `useSpringBoot4=true`; the generator's other options as that
  artifact's documentation gives them.

## To be checked

- The templates artifact is published, not only installed locally, before a build here depends on it.
