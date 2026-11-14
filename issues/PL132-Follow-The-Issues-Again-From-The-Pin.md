# PL132 — Follow The Issues Again From The Pin

```json
{
  "$schema": "issue-1.json",
  "id": "PL132",
  "title": "Follow The Issues Again From The Pin",
  "state": "Implementing",
  "size": "M",
  "priority": "Highest",
  "rank": 6,
  "blockedBy": [],
  "covers": "A click or right-click on a story's pin opens a menu with one entry, Follow the issues again, which removes the story's given state so it moves at once to the state its issues give; a story without issues is not offered it, since its head must hold a state"
}
```

**Size:** M - one contract change (the state a story's issues give), the planner's board answer and the screen's menu.

**What must be true.** A person who pinned a story to a state, or found one pinned, hands it back to its issues with
one click, and the story stands at once where its issues put it.

## Plan

- The planner already removes a given state when a story is moved to the state its issues give: `Moves.check` in
  `planner/core` puts it in `unstated`, and `GitStore` writes the head without a state. Only the store knows that state
  (`PlanningStore.storyState`, the workflow's rule), so the screen cannot work it out without a second copy of the rule.
- Contract: the board's story gains `derivedState` (string or null) in `planner.yaml`, the state its issues give by the
  workflow's rule; null when the board holds none of its issues or the rule cannot be applied. `BoardController` fills
  it from `store.storyState` over the held issues' states, as `Moves.check` does; the mock board and its served answers
  are recorded anew.
- Screen: `PlanStory.derivedState`; the pin becomes a button whose tap and secondary tap open a menu with the one entry
  "Follow the issues again", offered when the story's state is given and `derivedState` is not null. The entry moves the
  story to `derivedState` through the board's existing `move`, so the save, the refusals and the live update stay one
  path. The fake source unstates as the planner does.
- Red first: a `BoardReadsTest` case that a given story's answer carries the state its issues give, and null for one
  without held issues; a screen test that the entry moves the story and removes the pin, and that a story without issues
  shows no entry.
- The window's agent is told of the new member before the hand-over.

## Acceptance

- After the entry, the story stands in its issues' state with `stateGiven` false, on the screen and in its head; a
  story without issues never offers it.
