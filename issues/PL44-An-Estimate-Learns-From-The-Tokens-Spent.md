# PL44 — An Estimate Learns From The Tokens Spent

```json
{
  "$schema": "issue-1.json",
  "id": "PL44",
  "title": "An Estimate Learns From The Tokens Spent",
  "state": "Estimated",
  "size": "L",
  "priority": "Medium",
  "rank": 27,
  "blockedBy": ["PL61"],
  "covers": "A statistic per size and repository of the minutes and new tokens finished issues spent per phase is computed from Porelo's task records, and a task started for an estimate is handed it with the finished issue most like the new one"
}
```

**Size:** L - a token statistic per phase and a nearest finished issue in `core`, and a new placeholder of the work
rules' order, a format other repositories may read; it builds on the task records, issue attribution and velocity
that [PL61](README.md#pl61) brings.

**Estimate:** 113 min, the measured median of L. Uncertain: what Porelo's `totals` field splits the tokens into, and
whether the work rules' format may take a new placeholder without a change elsewhere.

**What must be true.** A task started to estimate an issue is told, per size of its repository, what finished issues
of that size cost in minutes and in new tokens per phase, and which finished issue is most like the new one, so a size
says something measured. The cost is told in tokens only; no price list is kept and no cost in money is shown.

## Plan

What stands today, read in the code, and what [PL61](README.md#pl61) already covers:

- Today the only number is minutes: `GitStore.velocity(repository, size)` over `StageRecords.statistic`, which counts
  `wall_min` of an agent's measured stages and reads the token fields (`claude_input_tokens`, ...) only to validate
  them. Nothing outside the store's tests calls `velocity`; no task the planner starts is given a number.
- [PL61](README.md#pl61) covers what this issue first planned for itself: the phase handed to Porelo's start, the
  port `TaskRecords` over `porelo task records --project <p> --since <t> --json`, an issue's share of each task (its
  sub-agent's span plus an even share of the task's own time, tokens counted the same way), the velocity in `core`
  with no Git, and the Git store's copy in `stats/<ID>.jsonl` when an issue is finished. Those parts are taken out
  of this issue.
- Left for this issue: the statistic of tokens, not only minutes, split by phase; handing the numbers to an
  estimating task; and the finished issue most like the new one.
- Taken out: a cost in money. Porelo's task record carries tokens, and a price per model would need a price list the
  planner keeps current; the statistic counts tokens only.
- Taken out as moot: the figures in the index's *Finished* row. The kept record already holds them, a second copy
  in Markdown goes stale, and the index format is shared with every repository's tools.
- The order a task is given is `WorkRule.order`, filled in by `WorkOrder.prompt`, whose `PLACEHOLDER` knows only
  `{story}`, `{issues}` and `{tasks}`; any other brace text stays as written.
- A finished issue's file is deleted, but `StoreReader` puts its index row on the board as an `Issue` with id,
  repository, title, size, state and the row's one sentence as its text. That is all the nearest issue needs, and
  every backend can answer it.

The steps:

1. **New tokens per issue and phase.** In `core`, beside [PL61](README.md#pl61)'s velocity, an issue's share of each
   task record's `totals` is summed per phase (the working state the task's `work` names): input without cache,
   cache write and output; cache reads are left out.
2. **The statistic.** `Cost.of(repository, size)` in `core`: over the repository's finished issues of that size
   (Finished, not Rejected), per phase and in total, the median and 80th percentile of minutes and of new tokens,
   the number of issues counted, and how often an issue took more than the median of the next larger size.
3. **The nearest finished issue.** Among the repository's finished issues on the board that have records, the one
   whose title and text share the most words with the new issue's, ties broken by the same size, then the lower id;
   its id, title, size, minutes and new tokens.
4. **Handed to the estimate.** `WorkOrder.prompt` gains the placeholder `{estimate}`: for each issue of the order,
   the statistic of its repository for every size and its nearest finished issue, as plain lines. A work rule that
   does not name it is unchanged. The app (`WorkRequests`) hands `core` the records; records Porelo cannot give fail
   the start with the reason, never a prompt with less.

Tests, red first, all unit tests over fakes:

- `core`, the statistic: two finished M issues with tasks of 1000 and 3000 new tokens in Implementing give a median
  of 2000 for M in Implementing; a cache read of a million changes nothing; a rejected issue counts for nothing.
  Harm: a size estimated from a wrong or doubled count.
- `core`, the nearest issue: of two finished issues, the one sharing more words is named; one of another
  repository never is. Harm: an estimate compared with unrelated work.
- `WorkOrderTest`: an order naming `{estimate}` carries the statistic and the nearest issue; one without it is
  byte for byte as today; records Porelo cannot give fail the start with the reason. The red first test: an order
  with `{estimate}` is expanded. Harm: an estimate started on numbers silently left out.

## Acceptance

- The tests above, each seen red first; the full build green.
- A task started from a work rule whose order names `{estimate}` gets, for its issue's repository, the minutes and
  new tokens per size and phase and the nearest finished issue, matching the task records they were computed from.

## To be checked

- What Porelo's `totals` holds per task - whether input without cache, cache write, cache read and output are
  separate - agreed in the channel.
- Whether another repository's tools read the work rules' order, so `{estimate}` needs agreement there before a
  project's workflow names it.
- Whether an issue resized while it was built counts under its final size or the size it was estimated at; the
  board gives only the final one.
