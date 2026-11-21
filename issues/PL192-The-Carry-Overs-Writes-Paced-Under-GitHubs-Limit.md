# PL192 — The Carry-Over's Writes Paced Under GitHub's Limit

```json
{
  "$schema": "issue-1.json",
  "id": "PL192",
  "title": "The Carry-Over's Writes Paced Under GitHub's Limit",
  "state": "Review",
  "size": "S",
  "priority": "High",
  "rank": 1,
  "blockedBy": [],
  "covers": "The carry-over makes its issues and comments at a pace under GitHub's limit on content-creating requests, waits out a call GitHub refuses as rate limited and makes it again, and says where it waits and until when, so a run of a real project goes on unattended to its CARRIED line"
}
```

**Why this size.** S: one pace in `store/github` wrapped around the carry-over's three call interfaces, wired in the
command, with its unit tests; no contract.

**What must be true.** A person who starts the carry-over of a project of more than a thousand items sees it run to
its end without starting it again, and sees on its error stream each wait, what it waits before and until when.

## Plan

- Today `CarryOver` writes through `ProjectWrites`, `CarryOverCalls` and `ProjectQueries` as fast as GitHub
  answers, and a call GitHub refuses as rate limited (`GitHubException.rateLimited()`, a 429, a 403 naming a rate
  limit or no requests left, or GraphQL's `RATE_LIMITED`) stops the run as `A call to GitHub failed`; the person
  starts it again and it goes on from its map. Measured by a dry run: the present project carries 1377 items, so
  1377 issues and 51 comments are made, about three times GitHub's documented 500 content-creating requests an hour.
- `CarryOverPace` (`store/github`) holds a clock, a sleeper and the line sink, and wraps each of the three
  interfaces (`writes`, `calls`, `queries`):
  - Making an issue and writing a comment are content-creating: each waits until a second has passed since the last
    such call and fewer than 450 stand in the last hour, so the run keeps under 80 a minute and 500 an hour. A wait
    longer than ten seconds says one line: what it waits before, why, and the time it goes on, in UTC.
  - Every other write (a body, a sub-issue, a dependency, the project's items and fields) stands a second after the
    last write, as GitHub asks of mutations, without counting against the hour; reads are not paced.
  - Any call GitHub answers with a refusal as rate limited is made again after a wait, since GitHub refuses such a
    call before it runs it; a call whose answer was lost may have taken effect and stops the run as before: the wait GitHub names (Retry-After, or the
    limit's reset), at least a minute doubling with each refusal in a row, at most an hour, eight times at most;
    each wait says one line with the call, the try and the time it goes on. A refusal that is no rate limit, and
    the ninth, are thrown as before, so the map is kept and the run stops loud.
  - The lines name a call by its kind and repository and number, never a title, a body or GitHub's words.
- `--per-minute <n>` (1 to 60, 60 by default) sets the spacing to a minute divided by n, for a token other work
  shares and for a trial that shows the waits; the hourly share stays 450.
- `tool/github-project-setup.sh <organization> <title> <stories repository>` makes the project, its fields and the
  label `story` the run needs, the token on standard input, and prints the project's number.
- `CarryOverCommand` wraps the remote's calls in the pace, its lines going through the command's scrubbed error
  stream; `Remote` gains the sleeper, a test's never sleeping.
- Red first: `CarryOverPaceTest` over a fake clock and sleeper: the 451st issue within an hour waits until the
  first is an hour old and says so; two issues one after the other stand a second apart; a call refused as rate
  limited with a wait of 30 s is made again after a minute, with its line, and then answers; a refusal that is no
  rate limit is thrown at once with no wait; nine refusals in a row are thrown after eight waits; a wait GitHub names
  as five hours is held to one; a write stands a second after the last without counting against the hour, a read
  is not paced, and the wrapped writes pace an issue and a comment at the run's pace; a rate-limited call whose
  answer was lost is not made again; four a minute stand issues 15 s apart and say each
  wait; a pace of 0 or 61 a minute is refused, by the pace and by the command. `CarryOverTest`: a run whose making
  of an issue is refused once as rate limited ends whole, the issue made once. `CarryOverRunTest`: a run at
  `--per-minute 4` waits 15 s between its issues and says so.

## Acceptance

- The unit tests above, seen red before the pace.
- A trial carry-over in the test organization, into a project made by `tool/github-project-setup.sh`, of a small
  project of three repositories with stories, sub-issues across them, blockers, stage records and an old id, runs
  unattended at `--per-minute 4` to its CARRIED line with each wait said; its duration and request rate are
  measured, the real run's duration inferred from them, and the board read back from GitHub equals the dry run.
- Measured in porelo-ai-test at `--per-minute 4`, native executable on the test VM: 12 items (3 stories, 9 issues
  in three repositories) and 2 stage comments carried in 230.9 s, 3.64 content-creating calls a minute, 4 waits
  said, no refusal; every item's fields, sub-issues, blockers, comments and rewritten links read back equal to the
  dry run, no old id on GitHub, a second run made nothing; the project, the repositories and their issues deleted
  after. Inferred for the real run of 1377 items and 51 comments: 1428 content-creating calls at 450 an hour fill
  three hours, about 2550 other writes a second apart add about 45 minutes, so about four hours.
