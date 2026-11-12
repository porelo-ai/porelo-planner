# PL84 — The Board Says A Story's Given State

```json
{
  "$schema": "issue-1.json",
  "id": "PL84",
  "title": "The Board Says A Story's Given State",
  "state": "Review",
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
- **The plan reading's points**, each checked in the code:
  - The screen reads and copies neither field today: `PlanStory.from` and `PlanStory.withMoves`. A planner older
    than this change sends no `misfits` and no `stateGiven`; `PlanStory.from` reads them as none and false and does
    not fail. After a save, `withMoves` keeps `stateGiven` (a story without issues that the save moved takes it as
    given, as the store writes it), keeps the misfits while neither the story nor one of its issues moved, and
    clears them otherwise; the board read that follows every save (`PlanningBoard.save`) then brings the
    planner's answer. A story with issues takes no destination in `withMoves`, so a confirmed move's given state
    and misfits arrive with that read. Tested at unit level.
  - An issue the board does not hold is neither a misfit nor read by the rule: `BoardController.issues` skips it,
    `Moves.statesOf` handed it to the rule as "", and the refusal named it as `id:null`. One rule in the core
    method; a story none of whose issues the board holds has none. The Git store already leaves another
    repository's issue out of a story, so this guards the mock and any other store.
  - The core method takes the issues' states and the injected rule (`store::storyState`), never a copy of the
    workflow's rule; `Moves.check` derives over the same issues and names its refusal's issues by it. The changes
    stream's `boardOf` has no store, so `Changes.Frame` carries the rule beside the board. Tested: a confirmed move
    leaves a story given and misfitting.
  - Edge cases, each a test: a given story with no issues has none; a given state its issues derive has none even
    while single issues stand elsewhere; given Review over only Finished issues makes each a misfit; the misfits
    come in the story's issue order.
  - `misfits` is filled through one helper in `BoardController.story` and `storyDetail`; `StoryDetail` is `Story`
    with a description, and the stream's `Board` carries `Story`, so both carry it.
  - `MockStore.story` already keeps a given state on a rebuild (only a story without one follows its issues), so it
    needs no change, only a test. No story of the mock board stood in a state its issues do not give, and the
    window's tests hold the Implementing column as it is, so the mock board gains ST295, given Review over SL37 in
    Review and SL38 in Backlog, priority Low; the screen's recorded mock answers are recorded again.
- **Built.**
  - `core`: `Misfits.of` holds the rule over the issues the board holds and the injected `Moves.StoryState`, and
    answers null, unknown, for a story whose rule throws. `Moves.check` derives over the same issues, names its
    refusal's issues by `Misfits.of`, takes a move of a story none of whose issues the board holds as one without
    issues, and its choice of a story's least advanced issues skips an issue the board does not hold, which before
    threw a `NullPointerException`. `WorkRun.derive()` hands out the store's rule, and `Changes.Frame` carries it.
  - `app`: OpenAPI `Story.misfits` (required, an `IssueId` array or null; `StoryDetail` takes it by `allOf`), and
    `stateGiven`'s description names the board and a confirmed move too; `Answers.Story` and `StoryDetail` gain
    `misfits`, filled by `Misfits.of` in `BoardController.story` and `storyDetail`, with the rule from the same
    `WorkRun` the board is viewed through; `boardOf` takes the rule, which the stream passes from its frame. The
    mock board gains ST295; the screen's `stories.json` and `board-event.json` were recorded again.
  - `screen`: `PlanStory` reads `stateGiven` and `misfits` leniently, `misfits` null when unknown, and keeps them
    through `withMoves` as above; `planningGivenMark` puts a pin beside the story's id and priority on the card and
    beside the state chip on the story page, its tooltip naming each misfit with its state, that the issues fit,
    or that this is not known.
  - The window's mock board, which `MockModeTest` holds equal to the planner's: `planning_data.dart` gains the same
    ST295; the stand-in's `settleStoryStates` keeps a given state and recomputes its misfits, and a story move
    drops it, as the planner unstates a story its issues fit.
  - Tests: `MisfitsTest` (the four edge cases, a misfit named, an issue the board does not hold, a rule that
    throws), `MovesTest` (a confirmed move's misfits match its refusal; a refusal names no issue the board does not
    hold; a story none of whose issues the board holds takes the state), `BoardReadsTest` (the stories, a story and
    the streamed board carry the misfits), `MockStoreTest` (ST295 keeps its given state and misfit through a save),
    `planning_contract_test.dart` (lenient reading, `withMoves`), `planning_given_state_test.dart` (the card's and
    the story page's mark) and `planning_moves_test.dart` (the stand-in keeps the given state and names its misfit
    until it fits).
  - No separate test for a derived state having none: a story's derived state is what its issues give, so no break
    of the rule can make that case fail.
- **The code reading's findings.**
  - One given story could fail the whole board: fixed. `Misfits.of` answers null for a story whose rule throws (a
    store without a read board, a state its workflow does not know), and the answer says null means unknown.
    Deriving from the answered board instead was not cheap: the rule lives in the Git store's workflow, not in the
    board's.
  - A refusal could name no issue: fixed. A story none of whose issues the board holds takes the state as one
    without issues, since there is nothing to fit.
  - The screen claimed "its issues fit it" when it did not know: fixed. `misfits` is null when unknown, after a save
    that moved the story or one of its issues and from an older planner, and the tooltip says it is not known.
  - The contract's words on an issue the board does not hold: fixed with the nullable `misfits`; they now say only
    the issues the board holds are read and named.
  - The stand-in dropped a moved story's given state: declined. The screen never sends `confirm`, so the planner
    it stands in for never keeps a mismatched story given: it unstates a story its issues fit and refuses any other.
    The stand-in takes every story move and already unstates; modelling the refusal would change the scenarios the
    stand-in's existing tests and the window's tests play, beyond this issue.

## Acceptance

- The board's answer says for every story whether its state is given and lists the issues that do not fit it; a
  story without a given state answers with none; the screen shows both; a card keeps its given state after the
  board is read again; every new test was seen red.
- The suite, the screen's Maven build and the documents' checks pass.

## Decided by the operator (2026-11-12)

- The board shows a given state as a mark beside the story's state; its tooltip names each misfit issue with its
  state.
