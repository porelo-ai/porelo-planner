# PL82 — The Commit Check Trusts What A Commit Says

```json
{
  "$schema": "issue-1.json",
  "id": "PL82",
  "title": "The Commit Check Trusts What A Commit Says",
  "state": "Review",
  "size": "S",
  "priority": "High",
  "rank": 1,
  "blockedBy": [],
  "covers": "The commit check gives no exemption, no trailer and no switch-off of the stage rule to whoever writes a commit, in the planner and in the build tools alike"
}
```

**Size:** S - one class of the Git store, `CommitRules`, and its unit tests; the fault lines are new, the exit
codes stay.

**Estimate:** 33 min, the measured median of S.

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

## Plan

What was built, in `planner/store/git/.../stories/CommitRules.java`:

1. **No exemption by name for a pull request from outside.** With `outside`, every commit is held to every rule,
   its author's `Signed-off-by:` among them, whatever author or committer it records; without it (a push, or a pull
   request from the repository itself) the bots' and GitHub's web merges stay exempt, since that writer may push
   anyway. The *Shared rules check* workflow passes no `--outside` for a pull request whose login is
   `dependabot[bot]` from the repository itself, as it already did for `github-actions[bot]`: the login is GitHub's
   word, not the commit's.
2. **The trailers are read too.** Every trailer's value joins the words searched for a requirement number; every
   trailer's value but a sign-off by the commit's own author or committer joins those searched for who asked or made
   it. `Co-authored-by` is no longer an allowed trailer, and a `Signed-off-by:` must give a name and an address.
3. **The stage rule cannot be switched off by deleting its files.** A commit whose tree lacks `stats/seed.csv` or a
   `stats/<id>.jsonl` that any of its parents held is refused, naming each file; a merge is compared with every
   parent, not only its first.
4. **Forged records.** The planner already counts the commits with `git rev-list --count` and gives no answer when
   the separators do not add up, pinned by `aMessageHoldingGitsSeparatorsIsNoAnswer`.

Tests: unit only, in `CommitRulesTest`, each new one seen red before its code: a bot's or GitHub's recorded name
exempts nothing from outside; a requirement number in any trailer, the sign-off's address among them (seen red under
a break of the trailer search); a credit in a trailer and in somebody else's sign-off; `Co-authored-by` and a sign-off
without an address refused; a deleted seed, a deleted stage record, and a merge dropping its second parent's seed
refused; a commit leaving `stats/` alone passes. Two older tests used a `Co-authored-by` trailer and a deleted stage
record as incidental fixtures and were changed to keep their own point.

## Acceptance

- Each case above refused with its fault line and exit 1, each test seen red first; `check-commits` on main's commits
  of the last day, as the scheduled check reads them, still passes.
