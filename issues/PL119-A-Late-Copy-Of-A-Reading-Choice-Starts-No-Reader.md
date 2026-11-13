# PL119 — A Late Copy Of A Reading Choice Starts No Reader

```json
{
  "$schema": "issue-1.json",
  "id": "PL119",
  "title": "A Late Copy Of A Reading Choice Starts No Reader",
  "state": "Implementing",
  "size": "S",
  "priority": "Low",
  "rank": 55,
  "blockedBy": [],
  "covers": "A choice on a stopped reading that arrives late, after the reading it chose has stopped again as unreadable at the same round, is refused instead of starting a reader the person never chose"
}
```

**Size:** S - the round check in `ReadingCycle` and what an unreadable stop records in `NextStep`, with their unit tests.

**What must be true.** A person's choice on a stopped reading is taken once. A delayed or resent copy of Read again that
arrives after the reader it started has ended unreadable again is refused, and the person sees the new stop.

## Plan

- **Today, read in the code.** `ReadingCycle.choose` takes a choice when its round equals `rounds.rounds()` and the
  board's revision is current. An unreadable stop counts no round, and neither the reader's start nor its unreadable end
  writes to the store, so the revision stays as well. A second `ONE_MORE_ROUND` with the old round and revision is
  taken, and offers another reader. Measured with a unit test over the fake store.
- **The change.** An unreadable reading counts as a round, and the round limit grows by one with it, so the person
  gets as many readings with findings as before and a late copy carries a round that no longer matches. The words for
  INVALID_FINDINGS in `planner.yaml`, the `Rounds` Javadoc and the changelog entry then say that the round is counted
  without using up the limit. The other way, a stop counter in the window's reading request, changes the contract
  with the window and is not taken.
- **Test, red first.** Unit, `WorkRunTest`: an unreadable stop, Read again, the new reader ends unreadable, and a copy
  of the first Read again is refused as ReadingRoundChanged. Harm: a reader run the person never chose.

## Acceptance

- The unit test, seen red against today's round check; the core and app suites green.
