# PL68 — A Project's Repositories Under Any Name

```json
{
  "$schema": "issue-1.json",
  "id": "PL68",
  "title": "A Project's Repositories Under Any Name",
  "state": "Idea",
  "size": "-",
  "priority": "Medium",
  "rank": 1,
  "blockedBy": [],
  "covers": "A project whose repositories are not named porelo-something is read and indexed like any other, and the smaller findings left beside a project on any host"
}
```

**What must be true.** A person whose project's repositories carry their own names, not `porelo-` and a suffix, sees
the same board, story texts and indexes as for the Porelo project, and a refusal says plainly what is wrong.

Found while a project on any host and owner was built ([PL42](README.md#pl42)), left for this issue:
- The repository names are still fixed to `porelo-` and a suffix in the story schema's issue pattern, the issue
  schema's wait pattern and the issues index's repository pattern; the name `porelo-project` is fixed in the issues
  index's waits and in the choice of the workflow file when local issues are read.
- The project's own central in `project.yml` is compared with the clone URL the project was opened with only by its
  last name, not by host and path, so its links may name another host.
- The issue links check skips every link holding `://` without looking at its host or scheme.
- A refused local path or `file:` central is shown as `<path>` after the scrub of Git's messages, so the refusal no
  longer names what was refused.
- A malformed `project.yml` is refused with the YAML parser's own message, which may quote a snippet of the line.
