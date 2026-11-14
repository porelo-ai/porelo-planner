# PL132 — Follow The Issues Again From The Pin

```json
{
  "$schema": "issue-1.json",
  "id": "PL132",
  "title": "Follow The Issues Again From The Pin",
  "state": "Review",
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
- A plain story move to that state is not enough: the planner carries the least advanced issues to a story's target
  and refuses a working state for a story, the screen drops a move to the state the story already has, and a move read
  from a stale board would carry issues backwards. So a story move gains `follow` (boolean, optional): with it the
  planner checks that the story's state is given, that the board holds every issue its head names, and that those
  issues give `to` now (else it refuses, naming the state they give), carries no issue, warns of nothing, and only
  removes the given state. A store that cannot write the result - the Git store keeps a story's tested day only on a
  completed story - refuses the save as today, and the screen says so.
- `derivedState` is null too when the board does not hold every issue the story's head names, so the screen never
  offers a state read from part of its issues. One public helper in `Misfits` answers it for both of
  `BoardController`'s story answers and returns null where the rule throws.
- Screen: `PlanStory.derivedState`; the pin becomes a button whose tap and secondary tap open a menu with the one entry
  "Follow the issues again", offered when the story's state is given and `derivedState` is not null, on the board and
  on the story's page. The entry calls `PlanningBoard.followIssues`, which sends the story move with `follow` through
  the board's one save path, so the refusals and the live update stay one path. The fake source does what the planner
  does and keeps `derivedState`.
- Red first: `MovesTest` cases that a follow move unstates without carrying an issue, also to a working state and to
  the state the story already has, and is refused when the issues give another state or one is not held;
  `BoardReadsTest` that a given story's answer carries `derivedState` and null where an issue is not held; a screen test
  that the entry removes the pin and moves the story, and that a story without issues shows no entry.
- The window's agent is told of the new members before the hand-over.
- Read in the code's reading, each fixed with a test seen red:
  - A story whose head names an issue the store left out has no `derivedState` and follows nothing: a store story says
    whether its issues are every one its head names.
  - A follow move touches no issue and no story's work, so its preview warns of no running work and its save stops
    none.
  - A follow move stands in a batch of its own, since judged after the batch's own issue moves its issues would give
    another state than the board's.
  - A follow into a closed state is refused for the state its issues give, not for the issues not being done.
  - A head of the first schema, which requires a state, names the second schema once its state is removed.
  - The pin's entry sends the state it offered, so an offer read before the issues moved is refused by the planner.
  - The mock refuses a follow as the planner does: in a batch with other moves, or with an issue in a state outside the
    workflow.
- Fixed without a test: a follow raises no local warning for a start the window handed on.
- Declined: after a stale board, the re-asked question with no warnings stays, as for every move: it tells the person
  that planning changed and asks again.

## Acceptance

- After the entry, the story stands in its issues' state with `stateGiven` false, on the screen and in its head; a
  story without issues never offers it.
