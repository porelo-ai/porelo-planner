# PL79 — The Work Commands' Smaller Findings

```json
{
  "$schema": "issue-1.json",
  "id": "PL79",
  "title": "The Work Commands' Smaller Findings",
  "state": "Idea",
  "size": "-",
  "priority": "Low",
  "rank": 1,
  "blockedBy": [],
  "covers": "The executable's issues and stories commands find project.yml only in the repository checked, quote no parse message of a linked file, encode the directory in a cut's links and refuse an empty issues directory"
}
```

**What must be true.** The executable's `issues` and `stories` commands read only the repository they are given and
answer a repository without issues as a refusal, not as a pass.

- `project.yml` is found by walking up every ancestor of the directory given; a directory far above the repository
  can answer instead of the repository's own.
- A `project.yml` that is a link is read through it, and a parse fault quotes a line of the file it leads to.
- A cut's links take the stories directory's name as it is, without URL-encoding, so a space or a `#` in it breaks
  the link.
- `issues check` of an empty directory passes, so a misnamed directory in a workflow checks nothing and stays green.
