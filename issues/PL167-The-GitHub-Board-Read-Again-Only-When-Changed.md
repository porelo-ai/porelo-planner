# PL167 — The GitHub Board Read Again Only When Changed

```json
{
  "$schema": "issue-1.json",
  "id": "PL167",
  "title": "The GitHub Board Read Again Only When Changed",
  "state": "Backlog",
  "size": "S",
  "priority": "High",
  "rank": 83,
  "blockedBy": ["PL165"],
  "covers": "While a window follows a GitHub project, the planner asks GitHub with conditional requests in parallel whether anything changed, reads the board again only then, and tells the window through its events stream; nothing unchanged costs rate limit, and no webhook needs an address reachable from outside"
}
```

**Why this size.** S, not M: the forge clients bring the conditional calls with their ETags and rate limits, so
what is left is a change probe in `store/github` before the full read, one interval setting in `planner/app`, and
tests with a fake clock; the window's events contract stays as it is.

**What must be true.** The board shows what changed on GitHub within the poll interval, and a board where nothing
changed is answered from what the planner holds, without spending the person's rate limit.

## Plan

- Today `ChangesRegistry` (`planner/app`) polls each followed project every `planner.poll-interval` (3 minutes) by
  `Changes.poll()` (`planner/core`), which calls the store's `refresh()` and sends a changed revision as an event.
- Webhooks are left out: they need an address GitHub can reach, and the planner listens only on the person's
  loopback. Polling stays.
- A background poll (`GitHubStore.poll()`) first asks, through `ConditionalChangeProbe`, the forge clients'
  `issuesIfChanged` for each repository and `projectItemsIfChanged` for the project, with the ETags of the last read,
  at most four at a time; when all answer unchanged (304) the board and its revision stay, and only a change runs
  [PL165](README.md#pl165)'s full read. GitHub counts no 304 against the rate limit. A person's reload
  (`refresh()`) always reads the whole project, since no change check sees a field or option made on GitHub.
- A read GitHub refuses as rate limited is `Unreachable` and names the wait ([PL165](README.md#pl165)). Until
  the wait ends, background reads are held off and the board kept is served.
- The interval for a GitHub project may be shorter than for Git (a probe costs little): `planner.github.poll-interval`,
  30 seconds by default; the rate limit left (`X-RateLimit-Remaining`) below a tenth lengthens it and says so as a
  board fault.
- When GitHub answers that the rate limit is spent, background reads are held off until GitHub's wait ends and the
  board kept is served to the window, with a fault saying when GitHub is read again; a person's explicit reload
  still asks once and gets the same refusal. One pace per person's token (`GitHubPace`) holds every project read
  with it, since GitHub counts the limit per person; a write is always followed by a whole read, never by the
  change check.
- Red first: `GitHubChangesTest` with a fake GitHub: three repositories answering 304 lead to no GraphQL query and no
  event; one 200 leads to one query and one event; a low rate limit lengthens the interval.

### Plan reading

The code reading by another model found these; each is answered here.

- **A rate-limit fault never reaching the window** (blocking): confirmed; a frame or a logged board with the same
  revision and work generation was dropped although its faults differed. Fixed: `Changes` and `BoardLog` count the
  faults in what makes a board new, and every board leaving `GitHubStore`, a write's included, carries the pace's
  faults.
- **A field or option made on GitHub never read** (blocking): confirmed; the probe asks no fields. Fixed by the
  reload: a person's reload always reads the whole project and never asks the probe; only the background poll does.
  A fault about a missing field or option therefore clears at the person's next reload or at the next change of an
  issue or item, not by the background poll alone.
- **A 304 without an ETag dropping the tag** (note): confirmed. Fixed: a 304 that names no tag keeps the one sent.
- **A new page behind a full last page** (note): confirmed. The forge client gives no headers on a 304, so the next
  cursor cannot be taken from its `Link` header; instead the last page, when it held a whole page of items, is asked
  without its tag at each look: the same tag and no next page is no change. This costs one counted request per look,
  only for a project whose items fill their last page exactly.
- **Tags from before a write taken for the board after it** (blocking): confirmed. Fixed: every whole read outside the
  probe (a write's read, a reload, a read with no board kept or another files commit) makes the probe forget its
  pending and confirmed tags, so the next poll reads whole once and starts anew.
- **A reload answered from the board kept while held** (blocking): confirmed. Fixed with the reload reading whole: it
  asks GitHub once and is told GitHub's refusal.
- **A wait of zero ending the hold at once and a refusal without words** (blocking): confirmed. Fixed: a zero or
  negative wait counts as GitHub naming none (one minute), and noting a refusal answers the words of the hold read at
  the same instant, never null.
- **An endless wait and a hold outliving a fresh answer** (blocking): confirmed. Fixed: a wait is capped at one hour,
  an answer with requests left ends the hold, and `ChangesRegistry` clamps its wait to a day.
- **A rate limit hidden by another ask's failure, and a whole read while held** (blocking): confirmed. Fixed: a look
  awaits every ask before it judges, a spent rate limit any of them met holds the pace, and no whole read follows a
  failed or changed look while the pace holds.
- **Both rate-limit faults at once** (note): confirmed. Fixed: the low fault is not shown while the limit is spent.
- **The board's faults waiting on a look** (blocking): confirmed; `faults()` and `unchanged()` shared one monitor.
  Fixed: the probe's lock covers only its state, never an ask, and `faults()` takes no lock of the probe.
- **Asks outliving their look** (blocking): confirmed. Fixed: a look cancels its asks when it ends, and the pages
  stop at the deadline or once the look is over.
- **An unbounded burst of asks** (note): confirmed. Fixed: the asks of one look run at most four at a time, by a
  semaphore in the probe.
- **Polls stopped for good by an escaping failure** (note): confirmed. Fixed: the next poll is scheduled in a
  `finally`.
- **A token or GitHub's words in a fault** (note): no leak found by the reading; the `"null"` refusal it names is gone
  with the atomic hold.
- **Three weak tests** (blocking): confirmed. Fixed: the whole read's spent limit and the reload while held are tested
  through `GitHubStoreOpener` with GraphQL reads refused and a fake GitHub that answers 304 while the limit is spent;
  a store that fetches keeps `PlanningStore`'s own wait.
- **`ConditionalReads.over` never tested** (note): confirmed. Fixed: `ConditionalReadsTest` runs it against a local
  server answering as GitHub does.

## Order and files

Built in this order, each pushed once what it needs is published: [PL164](README.md#pl164)'s types;
[PL169](README.md#pl169)'s contract (independent, at once); then [PL165](README.md#pl165), [PL125](README.md#pl125)
followed by [PL166](README.md#pl166), and [PL169](README.md#pl169)'s behavior followed by [PL167](README.md#pl167),
side by side; then [PL168](README.md#pl168). Each builds against the forge clients' published surface with fakes of
its own.

- Owns `store/github`: `ConditionalChangeProbe` (implements `ChangeProbe`); `planner/app`: the setting
  `planner.github.poll-interval` in `StoreConfiguration` and `ChangesRegistry`, after [PL165](README.md#pl165) has
  changed `StoreConfiguration`. Test: `ConditionalChangeProbeTest`.

## Acceptance

- Measured in the test organization: a look at an unchanged board takes the conditional requests only (the story
  measured 1.7 s for three), and a change made on GitHub reaches the window within one interval.

## To be checked

- Whether the project items' ETag changes when only a field value changes (the forge clients' recorder measures
  it); if not, the probe compares `projectFields`' `updatedAt` instead.
