# PL73 — A Story's Priority Changed On The Board

```json
{
  "$schema": "issue-1.json",
  "id": "PL73",
  "title": "A Story's Priority Changed On The Board",
  "state": "Backlog",
  "size": "M",
  "priority": "High",
  "rank": 1,
  "blockedBy": [],
  "covers": "A person changes a story's priority - Highest, High, Medium, Low or Lowest - on its page and from its card's menu, saved like a move in one commit with the stories index, and the board re-sorts by it"
}
```

**Size:** M - one contract change (a new call in the OpenAPI description) across the store, the app and the screen
package.

**Estimate:** 67 min, the measured median of M.

**What must be true.** A person changes a story's priority to Highest, High, Medium, Low or Lowest on the story's page and from its card's menu; the change is saved as a move is - the story's head and the stories index in one commit that starts no build - and the board sorts the story by its new priority.

## Plan

Read in today's code: a story's priority stands in its head's `priority` (`store/git` `stories/Story.java`) and the
stories index sorts by it (`StoriesIndex`); the board reports it as `Story.priority`, and the screen package sorts
each column by it (`PlanningBoard.rank`, `_sort`). Nothing writes it: a save's `Writes` carries issue states, story
states and workers, and `HeadEdit` edits a head's `state` and `worker` alone. `setWorker`
(`PUT /projects/{project}/issues/{issue}/worker`) is the pattern of a single field saved as a move is.

Steps:

1. Contract first: `PUT /projects/{project}/stories/{story}/priority` with the revision the window shows and one of
   `Highest`, `High`, `Medium`, `Low`, `Lowest`, answering the new revision as `setWorker` does, with its refusals
   (stale revision, unknown story, a value outside the five, save refused or unreachable).
2. `Writes` gains the stories' new priorities; `HeadEdit.storyPriority` replaces the head's `priority` line as
   `storyState` replaces the state, refusing a value outside the five. `GitStore` writes it before the indexes, so the
   stories index is rewritten in the same commit, whose subject ends in `[skip ci]`.
3. The app maps the call to the store; the mock mode, which saves nothing, answers it as it answers a move today.
4. The screen package: `PlanningSource.setPriority`, its HTTP source and fakes, a priority choice on the story's page
   and in the card's menu, unavailable while the board is not live or saving. The board re-sorts from the next
   snapshot.
5. Red first: unit in `HeadEditTest` (the line replaced, every other byte kept, a sixth value refused - harm: a head
   written wrong breaks every reader), `GitStoreSaveTest` (head and stories index in one commit) and an HTTP test
   against the description (`ApiMatchesDescriptionTest`); a widget test that the choice calls the source.

## Acceptance

- A priority chosen on the story's page or the card's menu is saved in one commit with the stories index, that
  commit starts no build, and the board sorts the story by it. Seen failing first: today's store saves no priority.

## To be checked

- That the window implements `PlanningSource` only through test fakes that extend `Fake`, so a new method of it needs
  no change in the window (asked of the window's agent).
