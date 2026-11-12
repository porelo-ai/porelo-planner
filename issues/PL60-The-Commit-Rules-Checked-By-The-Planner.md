# PL60 — The Commit Rules Checked By The Planner

```json
{
  "$schema": "issue-1.json",
  "id": "PL60",
  "title": "The Commit Rules Checked By The Planner",
  "state": "Review",
  "size": "L",
  "priority": "High",
  "rank": 1,
  "blockedBy": ["PL59"],
  "covers": "The rules on commit messages and the stage records a commit carries are checked by the planner's command line, `check-commits`, with the build tools' fault lines and exit codes"
}
```

**Size:** L - about 660 lines of checks (`CheckCommits`, `Who`) and 1,180 lines of tests taken over, the stage
record cross-check moved onto the planner's own `StageRecords`, and the command every repository's Build runs on each
push. The push check and the stage commands are split off into
[PL81](README.md#pl81), measured at about 2,700 lines more.

**What must be true.** Every repository's build checks its commits with the planner's command line, with the same
rules, fault lines and exit codes as `check-commits` gives today, and the stage records in `stats/` by the format the
planner writes.

## Plan

What stands today, read in the code:

- The build tools hold the rules in `CheckCommits` (a subject of at most 72 characters, only a blank line and the
  named trailers below it, no issue number of the repository's own prefixes, no party named or credit given as
  `Who` finds it, the bots' commits exempt by author and committer, every stage record closed, a stage record for
  each issue that enters or changes in a measured state, read in the commit and its first parent), `Who` (the names
  and phrases), `CheckPushes` (each push to `main` in a window, read from GitHub's repository activity with a token
  from the environment, each push's range checked as `check-commits` does), and `Stage` with `StageTokens` (`stage
  begin|end|median|p80`, the agent's own stage lines and their tokens read from a session file). They read the
  issue prefixes through the citations check and the JSON through the build tools' own `porelo-json`.
- The planner already holds the record format in `StageRecords` (`read`, `move`, `statistic`), a person's move
  (`actor: person`, `stage: move`) among the records, and writes such a move from `GitStore`.
- This repository's `build.yml` and `shared-rules.yml` run `check-commits` through `exec:exec@release`.

The commands join the planner's own command line in its executable, beside `issues` and `stories`; the checks that
read Git live in `planner/store/git`, so nothing outside it knows Git, and `WorkCommands` in `app` only calls them.
The stage commands move here later, with the push check.

The steps:

1. **`check-commits <range> [--outside]`** in `planner/store/git` (`CommitRules`, `Who` copied as it is), called from
   `WorkCommands`; exit 0, 1 (refused) and 2 (no answer) as the build tools give them, with their fault lines.
   - The range is refused when blank or starting with `-`, and git takes it after `--end-of-options` and before `--`;
     every other git call (`ls-tree`, `rev-list`, `diff-tree`, `show <hash>:<path>`) ends its revisions with `--`.
     The log's record and field separators and the six-field check stay; output git writes that is no UTF-8 is no
     answer. The log must hold one record separator and five field separators per commit, as `rev-list --count`
     counts them, or it is no answer: a message holding a record separator and five field separators would otherwise
     forge an exempt commit and hide its own body behind it, and a name holding a field separator would shift the
     fields.
   - A commit is exempt by its recorded author name `dependabot[bot]` or `github-actions[bot]`, or committer name
     `GitHub`, exactly; these are what the commit says of itself, as today, and an exempt commit skips the stage
     check too. `--outside` needs a `Signed-off-by:` trailer naming the author's address, compared without case.
   - The subject, trailer and requirement-number rules as today, the number pattern `[A-Z]{1,3}\d+` of the build
     tools, the prefixes read from the checkout's tracked files under `issues/` (or walked where there is no `.git`),
     no prefix at all being no answer.
   - Stage records, in each commit's tree as today: only where `stats/seed.csv` or a `stats/*.jsonl` stands, a
     missing `issues/workflow.yml` a fault, a missing seed a note once. Each `stats/*.jsonl` is read by
     `StageRecords.read(..., closed: true, ...)`; its `IllegalArgumentException` is the fault, a malformed line
     refused, never no answer. `StageRecords` is the rule: a person's move must be the one closed line the planner
     writes, stricter than the build tools, which never write a move. An issue's state and size come from its head
     through `IssueHead`, with the build tools' `**Status:**`/`**Size:**` fallback and its refusals (no valid size in
     a measured or closed state, a duplicate field); a measured issue that is new or changes state or size needs its
     `stats/<issue>.jsonl` in the tree, its presence enough; it is compared with the first parent only, and a parent
     version that cannot be read or is no valid issue is a fault, never taken as unchanged.
2. This repository's `build.yml` runs the planner's `check-commits` with the native executable its build step wrote,
   and `shared-rules.yml`'s daily run through `PlannerApplication` on the app's runtime classpath (`exec:exec@planner`
   in `planner/app`, after an install), in place of the build tools' `exec:exec@release` call, failing on any exit but
   0. A pull request keeps the build tools' pinned call while they carry the command, since the planner built from
   the pull request's own tree is code the pull request can change to pass itself; an unset push base or empty range still fails as today. Each line the command says is escaped as the other work
   tools' lines are. A changelog entry.

Tests, red first:

- Unit, `CommitRulesTest`, the build tools' `CheckCommitsTest` carried over (fake git for the rules, a fixture
  repository made with git under `GIT_CONFIG_GLOBAL=/dev/null` for the git calls): among them a range `--output=x`,
  `--all`, `-n1` or blank refused before git runs, a message holding the record or the field separator no answer, an
  author `dependabot[bot] ` (a trailing space) not exempt, a person's move as `StageRecords.move` writes it passing,
  a malformed JSON line refused, an open stage refused, a merge whose second parent changed a measured issue checked
  against the first parent only, a parent version that cannot be read refused. The build tools hold no `WhoTest`;
  `Who` is proven through the refusals of names and credits in `CommitRulesTest`.
- Integration, `CommitCommandTest` in `app`, test scope while the build tools still carry the command: both run as
  processes at one fixture repository's root, with the same exit code and lines for a pass, a long subject, an issue
  number, a prose body, `--outside`, an empty range, two ranges, `--all`, `-n1` and an unknown revision; a control
  character in a message said escaped; no build directory and no temporary file left behind.

## Acceptance

- The tests above, each seen red first; the full build green.
- This repository's Build and the shared-rules workflow's daily run use the planner's `check-commits` and are green on it; a
  deliberately long subject on a branch turns them red.
- The planner's executable gives `check-commits` with the build tools' exit codes, on one fixture run by both.
