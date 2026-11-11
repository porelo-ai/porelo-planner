# PL75 — The Work Record's Smaller Findings

```json
{
  "$schema": "issue-1.json",
  "id": "PL75",
  "title": "The Work Record's Smaller Findings",
  "state": "Idea",
  "size": "-",
  "priority": "Medium",
  "rank": 1,
  "blockedBy": [],
  "covers": "A refusal of the local work record names no account by its SID and no path glued to a word, and a lock whose release fails still closes its file"
}
```

**What must be true.** A refusal of the local work record names no account by its identifier, and leaves no path
unnamed; a lock whose release fails closes its file all the same, so a second planner is not held off until the first
exits.

Found in the reading of the code that made the work record say why it is unavailable:

- `WindowsAccess` words an owner as "belongs to <SID>, not to this account (<SID>)" and an entry as "lets <SID> in",
  and `WindowsPrivateFiles.answer` passes a child process's error output on with only its paths taken out. A SID is
  no credential, but it names the account; "another account" and "this account" say as much.
- `LocalPaths` leaves a path that follows `-`, `.`, a word character, `~`, `$` or `%` as it is (`-/etc/x`,
  `refused./home/x`), and names the `//h/p` of a URL as a path outside the home directory.
- `WorkRecord`'s entry closes its lock and its channel in one block: when the lock's release throws, the channel
  stays open and holds the lock until the process exits; `acquire` overwrites a channel whose lock is no longer valid
  without closing it.
- `LocalPaths.of` reads its bases from `Map.of`, whose order is unspecified, so when the state directory equals the
  home its name can differ between runs.
