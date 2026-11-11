# PL42 — A Project On Any Host And Owner

```json
{
  "$schema": "issue-1.json",
  "id": "PL42",
  "title": "A Project On Any Host And Owner",
  "state": "Review",
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

## Reading

The code was read by another model. Fixed, each with a test seen red first:
- A refused central never shows a credential: the refusal names the key and the value without its user information,
  query or fragment, and a value that cannot be split safely is not shown; the scrub of Git's own messages takes the
  same shapes (a password in the scp form, a password holding `@` or `/`, a token without a colon, a token in the
  query).
- A repository name follows the planner's own name rule, so an accepted central never fails later.
- A central is accepted only where the clone URL check accepts it too, one rule rather than two.
- The host is written in lower case and a port outside 1 to 65535 is refused.
- An echoed value is cut and its separators escaped.
- The planning repository's `project.yml` is read as the project's own reading reads it, and a missing one is named.
- `Project`'s refusals name the file and pass names through the safe form.

The rest, which only reads wrong or reaches beyond host and owner, is an issue of its own,
[PL68](README.md#pl68).

## Acceptance

- A project on another host and owner is read and indexed with links to its own host; a central that is no clone
  URL is refused with its key and value.
