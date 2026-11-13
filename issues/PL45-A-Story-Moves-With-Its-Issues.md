# PL45 — A Story Moves With Its Issues

```json
{
  "$schema": "issue-1.json",
  "id": "PL45",
  "title": "A Story Moves With Its Issues",
  "state": "Backlog",
  "size": "M",
  "priority": "Medium",
  "rank": 28,
  "blockedBy": [],
  "covers": "A story's state follows its issues where the hands are the planner's: it stands Implementing once its first issue is, Review once every issue is in Review, Finished or Rejected, and a story set Finished only once every issue stands there, moving none along"
}
```

**Size:** M - one story rule in the core used by the board's moves and by work, one guard of work loosened, the
contract's words on a story move, and the screen's prediction and fake board; the Git store already writes it. In
this repository only: the shared stories rule stays as it is.

**Estimate:** 67 min, the measured median of M.

**What must be true.** A person who moves an issue sees its story follow - Implementing with its first issue, Review
once all are in Review or done. A story goes to Finished only when every one of its issues already stands in Review,
Finished or Rejected; otherwise the move is refused with an error that says so, the story stays where it is, and no
issue is moved along.

## Plan

- **Today.** A story's state is its head's stated state, or else the stories index's rule, which `Workflow.storyState`
  in `store/git/stories` copies from the stories library: the earliest open issue state in workflow order. So a
  story with one issue in Implementing and one in Backlog stands Backlog; once every issue is in Review, Finished or
  Rejected it already stands Review. `GitStore.commitAndPush` already writes a stated story state
  (`HeadEdit.storyState`) beside the issues' heads, one commit per repository, the stories' repository pushed last.
- **Partly done.** `Moves.check` moves a story's least advanced open issues (`leastAdvanced`), so a story set
  Finished whose open issues all stand in Review already takes them to Finished, and `GitStore.settled`/`settle`
  settles each in the same commit. What is missing: issues in Review are left behind when another open issue stands
  earlier (it is moved instead) or later (Rework); the confirmed move then states the story Finished over them.
- **Where the rule lives.** The planner writes the state its rule gives into the story's head wherever the stories
  index's rule gives another; the shared stories rule, in the stories library and its copy here, stays as it is, so
  every reader of the index still reads the index's rule. `porelo-release stories index` warns for each such story,
  as `StoriesIndex.generate` already adds a WARN line for a stated state that differs from its issues; that warning
  is expected and is not a fault.
- **The rule, one class in `core`** (`StoryFollows`, plain Java): for a story with issues, the work's done state
  (Review, the `done` of the work rules) when every issue stands in it or closed and one stands in it; else the
  first working state in workflow order (Implementing) when any issue stands from there on and one is still open;
  else the index's rule. It answers what to state in the head: nothing when the index's rule agrees.
- **Moves.** `Moves.check` applies the rule to each story whose issues the batch moves, writing `stories` or
  `unstated`; `StoryStateMismatch` compares against the rule, not the index's rule alone.
  - **A story set Finished** (the completed state, closed, not Rejected) is refused unless every issue of it stands
    in the done state or closed, with an error naming the issues that do not; it moves no issue along, so today's
    `leastAdvanced` path for a story set Finished goes.
  - **An issue move in a story whose run has started** is refused as `WorkRunning`. How the window shows that
    refusal for an issue move is agreed with the window's agent before it is built.
- **Work.** `NextStep.writes` applies the same rule to the bound story, so a run's start states Implementing and its
  end states Review or unstates. `WorkRun.allowed` refuses work on a story stated in a working state without a run;
  it judges a story with issues by its issues' states only, or a stated Implementing would block the story's next
  repository for good.
- **Contract and screen.** `planner.yaml` (`saveMoves`, `Story.stateGiven`) says which issues a story move takes and
  that a story's state follows the rule; `PlanStory.leastAdvancedIssues` in `screen/lib/src/models.dart` takes no
  issue along on a move to Finished, and `settleStoryStates` in `screen/lib/src/fake/planning_moves.dart` follows
  the rule. Changelog entry; `doc/` where it describes a story's state.
- **Tests and the harm each prevents.** Unit, `core`, over the fake board:
  `StoryFollowsTest` (a story shown in the wrong state hides open work or stalls it), red first: the last of a
  story's issues in Implementing set to Review leaves the story Review, the first set Implementing states it
  Implementing; `MovesTest`: a story set Finished while one issue stands earlier than Review is refused and moves
  nothing, one whose issues all stand in Review or closed is moved alone, an issue move in a story whose run has
  started is refused as `WorkRunning`, a later issue move unstates the story when the rule agrees (a story closed
  over open work, or work moved under a running agent);
  `NextStepTest` and `WorkRunTest`: a run's start and end write the story's head, and a story stated Implementing
  with issues waiting in Backlog and no run is still offered work (a stalled story). Integration, `store/git`:
  one `GitStore` save of an issue move and of a story set Finished, read back from a fresh clone (heads written
  twice or not at all). Dart unit test of `leastAdvancedIssues` for the move to Finished, which takes none. No VM.

## Acceptance

- After each move or each run's start and end, the story's head and its issues' heads stand as the rule gives them,
  read from a fresh clone, in one commit per repository changed; a story stands stated only where the index's rule
  gives another state.
- Each named test is seen red with its part of the rule broken on purpose; `stories index` over the result reports
  no fault, and a WARN for each story whose head states a state the index's rule does not give.

## To be checked

- Name the first working state and the done state from the work rules (`Board.work()`) rather than by name, and
  confirm with today's `issues/workflow.yml` and the store's work rules that they read Implementing and Review.
- Confirm that `refuseFaults` and the store's read take a stated state that differs from its issues as a note, not
  a fault, by a `GitStore` test (the index only adds a WARN line today in `StoriesIndex.generate`).
