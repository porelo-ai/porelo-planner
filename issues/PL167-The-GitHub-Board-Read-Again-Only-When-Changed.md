# PL167 — The GitHub Board Read Again Only When Changed

```json
{
  "$schema": "issue-1.json",
  "id": "PL167",
  "title": "The GitHub Board Read Again Only When Changed",
  "state": "Analyzed",
  "size": "M",
  "priority": "High",
  "rank": 83,
  "blockedBy": ["PL165"],
  "covers": "While a window follows a GitHub project, the planner asks GitHub with conditional requests in parallel whether anything changed, reads the board again only then, and tells the window through its events stream; nothing unchanged costs rate limit, and no webhook needs an address reachable from outside"
}
```

**Why this size.** M: a change probe in `store/github`, its use in the existing polling (`ChangesRegistry`,
`Changes`), the interval setting, and tests with a fake clock; the window's events contract stays as it is.

**What must be true.** The board shows what changed on GitHub within the poll interval, and a board where nothing
changed is answered from what the planner holds, without spending the person's rate limit.

## Plan

- Today `ChangesRegistry` (`planner/app`) polls each followed project every `planner.poll-interval` (3 minutes) by
  `Changes.poll()` (`planner/core`), which calls the store's `refresh()` and sends a changed revision as an event.
- Webhooks are left out: they need an address GitHub can reach, and the planner listens only on the person's
  loopback. Polling stays.
- `GitHubStore.refresh()` first asks each repository's issue events and the project's items with
  `If-None-Match` (the ETags of the last read), in parallel; all 304 answers keep the board and its revision, and
  only a 200 runs [PL165](README.md#pl165)'s full query. GitHub counts no 304 against the rate limit.
- The interval for a GitHub project may be shorter than for Git (a probe costs little): `planner.github.poll-interval`,
  30 seconds by default; the rate limit left (`X-RateLimit-Remaining`) below a tenth lengthens it and says so as a
  board fault.
- Red first: `GitHubChangesTest` with a fake GitHub: three repositories answering 304 lead to no GraphQL query and no
  event; one 200 leads to one query and one event; a low rate limit lengthens the interval.

## Acceptance

- Measured in the test organization: a look at an unchanged board takes the conditional requests only (the story
  measured 1.7 s for three), and a change made on GitHub reaches the window within one interval.

## To be checked

- Which conditional request sees every change the board shows: a field change in a GitHub project may not touch the
  repository's issue events, so the project's own items endpoint or `updatedAt` may be needed.
- With Agent Gorge: conditional requests (`If-None-Match`, a 304 answer, the ETag kept by the caller) in
  `GitHubClient`.
