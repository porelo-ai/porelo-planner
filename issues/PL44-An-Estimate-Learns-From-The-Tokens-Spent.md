# PL44 — An Estimate Learns From The Tokens Spent

```json
{
  "$schema": "issue-1.json",
  "id": "PL44",
  "title": "An Estimate Learns From The Tokens Spent",
  "state": "Accepted",
  "size": "L",
  "priority": "Medium",
  "rank": 27,
  "blockedBy": [],
  "covers": "When an issue is set Finished, the Git store keeps what its tasks spent in tokens per phase beside its estimated size; a statistic per size and repository is kept from those records, and a task started for an estimate is handed it with the finished issue most like the new one"
}
```

**Size:** L - the store's record of a finished issue, the statistic, and the estimate's start, on top of the stage records and velocity the Git store takes over; the tokens come from Porelo's usage record.

**What must be true.** Before an issue is Finished, the tokens actually spent on it are kept beside the size it was estimated at; every finished issue adds to a statistic of what a size costs, and a new estimate is compared with finished issues like it, so a size says something measured.

## Plan

- The planner starts every task for an issue itself, with its issue and phase (analysis, build, rework); Porelo's usage record gives each task's new tokens - input without cache, cache write and output; cache reads are left out.
- Setting an issue Finished writes one JSON record for it in its repository - issue, estimated size, new tokens per phase, cost with its price date, tasks counted, a one-line kind of work - and the row under Finished shows the same figures, rendered from the record.
- The statistic per size and repository - middle, spread, how often a size was exceeded - is computed from the records, never from Markdown; the start of an estimate hands it with the finished issue most like the new one in kind of work, beside the minutes' velocity.
- Red first: an issue with two counted tasks set Finished leaves a record whose tokens per phase are their sums.

## Acceptance

- A finished issue's record holds its tokens per phase and is read back into the statistic; a task started for an estimate carries the statistic and its nearest finished issue.
