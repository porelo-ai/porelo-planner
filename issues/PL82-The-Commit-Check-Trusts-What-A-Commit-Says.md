# PL82 — The Commit Check Trusts What A Commit Says

```json
{
  "$schema": "issue-1.json",
  "id": "PL82",
  "title": "The Commit Check Trusts What A Commit Says",
  "state": "Idea",
  "size": "-",
  "priority": "Medium",
  "rank": 1,
  "blockedBy": [],
  "covers": "The commit check gives no exemption, no trailer and no switch-off of the stage rule to whoever writes a commit, in the planner and in the build tools alike"
}
```

**What must be true.** Nobody who writes a commit can make `check-commits` skip a rule by what the commit says of
itself; the planner's command and the build tools' `check-commits` stay alike while both carry it.

- The bots' exemption is given by the author's or committer's name the commit records, `dependabot[bot]`,
  `github-actions[bot]` or committer `GitHub`; a pull request from outside whose commits are made under such a name
  skips every rule, its author's `Signed-off-by:` among them.
- The trailers below a message are never searched for an issue number or a name, and `Co-authored-by:` is an allowed
  trailer, though the shared rules say an agent never writes one.
- A commit that deletes `stats/seed.csv` and the stage records switches the stage rule off, and a later commit that
  brings the seed back with issues already in measured states passes, since their state is unchanged against its
  parent; a stage record deleted on its own is never missed.
- The build tools split git's log by its record and field separators without counting the commits, so a message that
  holds a record separator and five field separators forges a second, exempt commit and hides its own body behind
  it; the planner counts the commits with `git rev-list --count` and gives no answer when the separators do not add
  up.
