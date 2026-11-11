# PL60 — The Commit And Push Rules Checked By The Planner

```json
{
  "$schema": "issue-1.json",
  "id": "PL60",
  "title": "The Commit And Push Rules Checked By The Planner",
  "state": "Analyzed",
  "size": "L",
  "priority": "High",
  "rank": 1,
  "blockedBy": ["PL59"],
  "covers": "The rules on commit messages and pushes are checked by the planner's command line, so the build tools hold none of the work format",
  "openQuestions": 1
}
```

**Size:** L - about 1,900 lines of checks and 3,000 lines of tests taken over, a GitHub client of the planner's
own, and the command every repository's Build runs on each push.

**What must be true.** Every repository's build checks its commits and its pushes to `main` with the planner's command
line, with the same rules, fault lines and exit codes as `check-commits` and `check-pushes` give today, and the stage
records in `stats/` by the format the planner writes.

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

The steps, all in the work tools' module and its command line:

1. **`check-commits <range> [--outside]`**: the rules above taken over with their tests; the stage-record part reads
   through `StageRecords`, so a person's move passes as the planner writes it; the issue prefixes from the
   repository's own issue files and index rows through `IssueLinks`; JSON through Jackson. Git is called as a
   process with the range never taken as an option.
2. **`check-pushes --since <hours>`**: GitHub's activity read with `java.net.http`, the token from the environment
   only, never printed, never in an argument or an error; pages, the push kinds and the refusal of a full page as
   today.
3. **`stage begin|end <issue> <stage> ...` and `stage median|p80 <size>`**, if the operator moves it here: the
   options, the one open stage per agent, the session file's tokens and the refusal of `begin ... move` as today.
4. This repository's workflows run the planner's own command; `AGENTS.md` says that the work tools' module knows
   Git besides `store/git`, for the commit checks; a changelog entry.

Tests, red first:

- Unit, `CheckCommitsTest` over a fixture repository made with git (`GIT_CONFIG_GLOBAL=/dev/null`): a subject of 73
  characters and one naming an issue number are refused with today's fault lines; a person's move record passes; an
  open stage is refused; a bot's commit is exempt by author, not by its message.
- Unit, `CheckPushesTest` over a fake web answer: a push whose range holds a refused commit fails; a full page is
  refused; the token appears in no output line.
- Unit, `WhoTest` and `StageTest` carried over, the move and its refusal as `begin` among them.
- Integration, test scope while the build tools still carry the commands: the same fixture refused alike by both.

## Acceptance

- The tests above, each seen red first; the full build green.
- This repository's Build and shared-rules workflow run the planner's `check-commits` and are green on it; a
  deliberately long subject on a branch turns them red.
- A published snapshot of the work tools gives `check-commits`, `check-pushes` (and `stage`) with the build tools'
  exit codes.

## Open questions

- Does `stage begin|end|median|p80`, with which an agent writes its own stage lines, move into the planner's
  command line here? It is proposed to move it, since the build tools drop it and the stage lines count until the
  record Porelo keeps of each task takes their place.

## To be checked

- Which of the build tools' tests read only a wording, which are then not carried over.
- Whether `check-commits` keeps reading a sibling `seed.csv` as today and how the person's move enters the
  cross-checks against the issue's head, read in `Stage.issue` and `StageRecords`.
