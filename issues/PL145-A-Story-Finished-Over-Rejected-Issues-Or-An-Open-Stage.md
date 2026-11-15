# PL145 — A Story Finished Over Rejected Issues Or An Open Stage

```json
{
  "$schema": "issue-1.json",
  "id": "PL145",
  "title": "A Story Finished Over Rejected Issues Or An Open Stage",
  "state": "Idea",
  "size": "-",
  "priority": "Medium",
  "rank": 65,
  "blockedBy": [],
  "covers": "A story moved to Finished whose issues are all Rejected, or none, and one whose carried issue has an unclosed stage record, is either saved whole or refused with one line why, never failing in the store after the preview passed it"
}
```

**What must be true.** A person who moves a story to Finished sees the move saved or refused with its reason at once,
never a save the store fails on afterwards.

## To be checked

- Whether a story whose issues are all Rejected, or that has none, moved to Finished is written as a given state with
  no `tested` date, which the stories index then refuses.
- Whether a carried issue with an unclosed stage in its stage records makes the store's stage record of the move fail,
  and whether a carried sub-issue gets a stage record at all.
- Each is reproduced by a unit test over the Git store before anything is built.
