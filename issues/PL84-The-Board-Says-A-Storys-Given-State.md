# PL84 — The Board Says A Story's Given State

```json
{
  "$schema": "issue-1.json",
  "id": "PL84",
  "title": "The Board Says A Story's Given State",
  "state": "Implementing",
  "size": "M",
  "priority": "Medium",
  "rank": 29,
  "blockedBy": [],
  "covers": "The planner's answers say whether a story's state was given to it rather than derived from its issues, and which of its issues do not fit that state, so the board can show both"
}
```

**Size:** M - one contract change, the board's `Story` gaining the issues that do not fit its given state, carried
from the core through the app to the screen, which shows both; no other repository reads that answer but the window
through the screen.

**Estimate:** 67 min, the measured median of M.

**What must be true.** A window that reads a story from the planner learns whether its state was given to it - on the
board, by a confirmed move, or in its head - or derived from its issues by the automatic rule, the earliest open state
of its issues, and, for a given state, each issue that does not fit it with its own state; a story without a given
state answers as today.

## Plan

- **Today, read in the code.** The store keeps a story's given state (`Story.stateGiven`) and the board's answer
  already carries it: OpenAPI `Story.stateGiven` (required, boolean), filled in `BoardController.story` (near line
  863) and `Answers.Story`. What is missing is which issues do not fit: `Moves.mismatch` computes them only to refuse
  an unconfirmed move (`StoryStateMismatch`, parameter `issues` as `id:state`), and the stories index only warns
  (`StoriesIndex`, "stated X, its issues give Y"). The screen's `PlanStory` (`screen/lib/src/models.dart`) reads
  neither, so the board shows a given state like a derived one.
- **The rule, one copy in the core.** A story whose state is given and whose issues give another state (the
  workflow's `storyState` over its issues' states) has as misfits each of its issues whose state is not the given
  one; a story whose issues give its state, or whose state is not given, has none. A small pure method in `core`
  beside `Moves`, which `Moves.mismatch` then uses too, so the refusal and the answer never disagree.
- **Contract and app.** OpenAPI `Story` and `StoryDetail` gain `misfits` (required, an array of `IssueId`, empty when
  none), written in the description first; `Answers.Story`/`StoryDetail` and `BoardController.story` fill it; the
  mock board gives one story a given state with a misfit, so the window's tests see one.
- **Screen.** `PlanStory` reads `stateGiven` and `misfits`; the story card and the story page show that the state was
  given with a mark beside the state, whose tooltip names each misfit issue with its state.
- **Tests, by level, red first.**
  - Unit, core: the misfit rule over a given state its issues do not give, one they do give, and a derived state;
    `Moves`' refusal names the same issues. Harm: a story shown as fitting while its issues stand elsewhere, so it is
    taken as done.
  - Integration, app: the board's answer carries `misfits` and matches the description
    (`ApiMatchesDescriptionTest`). Harm: the window reading a field the contract does not name.
  - Widget, screen: a story with a given state and one misfit shows the mark and the misfit's state; a derived one
    shows neither. Harm: the person not seeing that a story's state stands against its issues.

## Acceptance

- The board's answer says for every story whether its state is given and lists the issues that do not fit it; a
  story without a given state answers with none; the screen shows both; a card keeps its given state after the
  board is read again; every new test was seen red.
- The suite, the screen's Maven build and the documents' checks pass.

## Decided by the operator (2026-11-12)

- The board shows a given state as a mark beside the story's state; its tooltip names each misfit issue with its
  state.
