# PL82 — The Commit Check Trusts What A Commit Says

```json
{
  "$schema": "issue-1.json",
  "id": "PL82",
  "title": "The Commit Check Trusts What A Commit Says",
  "state": "Implementing",
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

Read in today's code, `planner/store/git/.../stories/CommitRules.java`:

1. **No exemption by name for a pull request from outside.** `Commit.exempt()` trusts the recorded author
   (`EXEMPT_AUTHORS`) and committer (`WEB_COMMITTER`), which whoever writes a commit sets. `check(range, outside)`
   gives the exemption only when `outside` is false - a push or a pull request from a branch of the repository itself,
   whose writer already has the right to push; with `outside` every commit is held to every rule, its author's
   `Signed-off-by:` among them. Red first, unit, `CommitRulesTest`: a commit of author `dependabot[bot]` and one of
   committer `GitHub`, with a requirement number and no sign-off, checked with `outside`, are refused with exit 1.
2. **The trailers are read too.** The value of every trailer joins the words searched for a requirement number, and
   every trailer but `Signed-off-by:` (which names its author by design) the words searched for who asked or made it.
   `Co-authored-by` leaves `TRAILER`: below the subject it is a line that is not an allowed trailer. Red first, unit:
   `Assisted-by: PL82` and `Co-authored-by: A <a@example.org>` are each refused.
3. **The stage rule cannot be switched off by deleting its files.** A commit whose first parent's tree holds
   `stats/seed.csv` or a `stats/<id>.jsonl` the commit's tree lacks is refused, naming each file it deletes; the seed
   brought back later then has no passing commit that deleted it. Red first, unit: a commit deleting the seed, and one
   deleting one stage record alone, are each refused; a commit that changes nothing under `stats/` still passes.
4. **Forged records.** The planner already counts the commits with `git rev-list --count` and gives no answer when
   the separators do not add up; a unit test pins it: a message holding a record separator and five field separators
   exits 2. The build tools' copy is patched by their own agent.

Tests: unit only, in `CommitRulesTest` over its fake `Git`; each seen red under a break of `CommitRules`. The
`check-commits` command's own test, `CommitCommandTest`, runs unchanged.

## Acceptance

- Each case above refused with its fault line and exit 1 (exit 2 for the forged record), each test seen red first;
  `check-commits` on this repository's own history since the stage rule's rollout still passes.

## To be checked

- Whether a stage record is ever deleted on purpose (a rejected issue's), and if so, which commit may do it.
- How the workflows decide `outside`: Dependabot's pull requests come from a branch of the repository itself and
  must keep their exemption.
