# PL73 — A Story's Priority Changed On The Board

```json
{
  "$schema": "issue-1.json",
  "id": "PL73",
  "title": "A Story's Priority Changed On The Board",
  "state": "Review",
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
3. The app maps the call to the store under the project's work lock; the mock mode answers it not offered.
4. The screen package: `PlanningSource.setPriority`, its HTTP source and fakes, a priority choice on the story's page
   and in the card's menu, unavailable while the board is not live or saving. The board re-sorts from the next
   snapshot.
5. Red first: unit in `HeadEditTest` (the line replaced, every other byte kept, a sixth value refused - harm: a head
   written wrong breaks every reader), `GitStoreSaveTest` (head and stories index in one commit) and an HTTP test
   against the description (`ApiMatchesDescriptionTest`); a widget test that the choice calls the source.

Taken from the plan's reading:

- A save that only sets priorities passes the stories-index gate (`storiesIndexed` in `GitStore.commitAndPush`), its
  heads are among the changed paths, and its subject has its own wording, ending in `[skip ci]`; a test saves a
  priority alone.
- A story moved and given a priority in one save has its head edited once, with both; the call itself carries only a
  priority, so this arises only inside the store.
- `HeadEdit.storyPriority` uses its own pattern and needs exactly one `priority` line: none or two refuse the save
  instead of inserting or guessing; the value is checked against the five names, not the general plain-word check.
- An unchanged priority saves nothing and answers the current revision, as `setWorker` does. A value outside the five
  is a refusal (400), never a 500. A story whose file is gone answers as an unknown story.
- A priority may change while the story's run is at work: it touches no issue, and a race with a push answers stale
  revision or save refused, with nothing partly written. The call runs under the project's work lock, as `setWorker`
  does.
- The mock mode, which saves nothing, answers it not offered (501), as `setWorker` does there.
- The screen shows the chosen priority as pending until the board's snapshot carries it, and a story without a
  reported priority shows no choice selected.

## Acceptance

- A priority chosen on the story's page or the card's menu is saved in one commit with the stories index, that
  commit starts no build, and the board sorts the story by it. Seen failing first: today's store saves no priority.

## Code reading

Read by another model; nothing grave. Declined, each harmless as built:

- In the mock mode an unchanged priority answers its current revision before the store is asked, as an unchanged
  worker does; a changed one is not offered.
- A head without exactly one priority line is refused as a move is (422), naming the story.
- A save never carries an idea and a priority together, so the subject's idea wording does not hide one.
- A newer board that does not yet carry a chosen priority shows the old one until the board that carries it arrives;
  only the display flickers.
- The window builds against `MockPlanningSource`, which offers `setPriority`, so the window needs no change.
