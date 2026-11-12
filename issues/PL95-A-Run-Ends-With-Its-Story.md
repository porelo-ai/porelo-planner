# PL95 — A Run Ends With Its Story

```json
{
  "$schema": "issue-1.json",
  "id": "PL95",
  "title": "A Run Ends With Its Story",
  "state": "Idea",
  "size": "-",
  "priority": "High",
  "rank": 1,
  "blockedBy": [],
  "covers": "The planner's local record of a story's run ends with the story: a run whose story is gone from the store is dropped, and a story saved anew under an old ID starts without the run recorded before it"
}
```

**What must be true.** The planner keeps each story's run in its own local work record, apart from the store. When the
story's file leaves the store (a reset, a deletion, a story rejected and removed), its run must not outlive it. A new
story that is saved under the same ID, with issues under the same IDs, must start with no run. Today the old run is
taken over: the new story shows the old tasks and "The run ended", and "Start work" is refused because the run needs
attention, until someone removes the record by hand.
