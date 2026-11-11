# PL45 — A Story Moves With Its Issues

```json
{
  "$schema": "issue-1.json",
  "id": "PL45",
  "title": "A Story Moves With Its Issues",
  "state": "Accepted",
  "size": "M",
  "priority": "Medium",
  "rank": 28,
  "blockedBy": [],
  "covers": "A story's state follows its issues where the hands are the planner's: it stands Implementing once its first issue is, Review once every issue is in Review, Finished or Rejected, and a story set Finished sets its issues still in Review to Finished with it"
}
```

**Size:** M - the Git store's story writes and the board's moves, over the story state rule the store takes over.

**What must be true.** A person who moves an issue sees its story follow - Implementing with its first issue, Review once all are in Review or done - and a story the person sets Finished after its test takes its issues still in Review with it.

## Plan

- Today a story's state is computed from its issues when its head names none, or taken from its head; nothing writes it when its issues move, and a story set Finished leaves its issues in Review.
- A move of an issue rewrites its story's head where the rule gives the story another state, in the same commit; setting a story Finished moves its issues still in Review to Finished in the same commit, each settled as a finished issue is.
- Red first: setting the last issue of a story in Implementing to Review leaves the story in Review in the same commit.

## Acceptance

- The story's head and its issues' heads stand as the rule gives them after each move, read from a fresh clone, in one commit per move.
