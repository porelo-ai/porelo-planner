# PL139 — A Story Finished Takes Its Issues In Review

```json
{
  "$schema": "issue-1.json",
  "id": "PL139",
  "title": "A Story Finished Takes Its Issues In Review",
  "state": "Review",
  "size": "M",
  "priority": "Medium",
  "rank": 62,
  "blockedBy": [],
  "covers": "A story moved to Finished takes every one of its issues in the state its work leads to, Review, to Finished in the same save, each finished as its own move to Finished is: settled by the Git store and its stage record written; issues in any other state are refused as today"
}
```

**Size:** M - one rule of the core's moves, its mirror in the screen package, their tests and the window's pin; the
contract's members stay.

**What must be true.** A person who has tested a story moves it to Finished once, and the story and each of its issues
in Review stand in Finished, instead of the story alone with a warning that its issues in Review do not fit it.

## Plan

- Today `Moves.plan` (`planner/core`) carries no issue for a story moved to a completed state: "a story is completed
  over its issues' own moves". `checkCompleted` lets the move pass when every issue, sub-issues included, stands
  closed or in the state the work rules' last work leads to (Review); the story then stands in Finished as a given
  state, and the preview's misfit warning lists the issues in Review with the state they give, Review, so it reads
  "Review → Review". The screen mirrors the rule in `PlanStory.leastAdvancedIssues`
  (`screen/lib/src/models.dart`), which expects no carried issue for a completed target.
- `Moves.plan`: a story moved to a completed state carries each of its issues, and each sub-issue of them, that stands
  in the work rules' last done state, to the target. `checkCompleted` keeps refusing any issue in another open state,
  naming it, as today. The carried issues reach the preview's `ISSUES_ALONG` warning, which already names issues of
  other repositories moving along, so the person confirms them; the story then fits its issues and is no given
  state, and no misfit warning stands.
- The screen: `leastAdvancedIssues` answers, for a completed target, the issues in the done state the planner carries,
  so `_moveNow` expects exactly those as moved and the board shows them in Finished at once. The window needs no part
  of its own beyond the pin raise to the screen's commit, which Agent Frontend files.
- How the move finishes an issue: exactly as the issue's own move to Finished does today, so a story's move equals
  moving its issues one by one. The Git store writes its stage record (`GitStore.recordMoves`) and settles it in the same
  save (`GitStore.settle`): its index row loses the link to its file, and the file is deleted. What an issue still holds
  that outlives it must be kept elsewhere before the move; the preview's warning lists the carried issues, so the person
  sees which files go.
- The move is checked against the board's states, before any other move of the same batch: an issue that reaches
  Review only through another move of the batch is not carried. A Review sub-issue under a Rejected parent is refused.
  A carried issue that also belongs to another open story is named in the `ISSUES_ALONG` warning.
- The screen reads the last done state from the work rules the snapshot holds, never a fixed Review.
- Red first, in `MovesTest`: a story with issues in Review moved to Finished carries them and has no misfit; one with
  an issue in Implementing is still refused naming it; a Review sub-issue is carried; a story moved to Rejected carries
  nothing. In the screen's `planning_moves_test.dart`: the move of such a story shows its issues in Finished after the
  save. A forged batch that moves an issue to Review beside the story's move is refused; a Review sub-issue under a Rejected
  parent is refused; an issue of a second open story is named in the warning. Harm: a story finished while its issues
  stay open for good, an issue finished that was never reviewed, and a save the screen does not show as made.

- Declined from the code's reading: after a story's move that carries issues and leaves the story with a state of its
  own, the board shows the carried issues at once and the story where it stood until the board is read again right
  after the save, as for every story move that carries issues; nothing is lost or shown as saved that was not.

## Acceptance

- The unit tests above, seen red against today's rule; the core's, the Git store's and the screen's suites pass; a
  story with issues in Review moved to Finished on the mock board shows them in Finished.
