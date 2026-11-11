# PL42 — A Project On Any Host And Owner

```json
{
  "$schema": "issue-1.json",
  "id": "PL42",
  "title": "A Project On Any Host And Owner",
  "state": "Backlog",
  "size": "S",
  "priority": "Medium",
  "rank": 20,
  "blockedBy": [],
  "covers": "The Git store reads a project whose repositories live on any host and under any owner: each central from any clone URL - https, ssh or scp-like - and the link pattern and host of story texts and indexes from project.yml instead of one fixed owner"
}
```

**Size:** S - one module, `store/git`, and its tests; `project.yml` is only read, its format does not change.

**What must be true.** A person whose project's repositories live on another host or under another owner than the
Porelo project's own sees the same board and gets the same story texts and indexes: the planner reads every
repository's central from its clone URL and writes links that name the project's own host.

## Plan

- Once the stories library's code is part of `store/git`, its reading of `project.yml` accepts a central given as
  `https://`, `ssh://` or scp-like (`user@host:path`), on any host and under any owner, the repository's name being
  the last part of the path without `.git`; anything else is refused naming the key and the value.
- The link pattern and the central's host that story texts and indexes are written with come from the project's
  `project.yml`, not from a host and owner written into the code.
- Red first: a `project.yml` whose centrals live on another host and owner is read with the right names, and the
  index written for it links to that host. Unit tests over files in a temporary directory.

## Acceptance

- A project on another host and owner is read and indexed with links to its own host; a central that is no clone
  URL is refused with its key and value.
