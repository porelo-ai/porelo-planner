# PL139 — A Story Finished Takes Its Issues In Review

```json
{
  "$schema": "issue-1.json",
  "id": "PL139",
  "title": "A Story Finished Takes Its Issues In Review",
  "state": "Backlog",
  "size": "M",
  "priority": "Medium",
  "rank": 62,
  "blockedBy": [],
  "covers": "A story moved to Finished takes every one of its issues in the state its work leads to, Review, to Finished in the same save, writing each issue's head as Finished and leaving its file for its repository's owner to finish; issues in any other state are refused as today"
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
- How the move finishes an issue: the Git store writes the issue's head as Finished, as an issue's own move to
  Finished does today, and the index gives it a closed row linked to its file with its covers. It does not delete the
  file, write the row's sentence of what was built, or move what outlives the issue to the documents or the decisions:
  that is a judgment of the text, left to the repository's owner as the Finished rule's own commit. The move never
  refuses for a text that still holds something.
- Red first, in `MovesTest`: a story with issues in Review moved to Finished carries them and has no misfit; one with
  an issue in Implementing is still refused naming it; a Review sub-issue is carried; a story moved to Rejected carries
  nothing. In the screen's `planning_moves_test.dart`: the move of such a story shows its issues in Finished after the
  save. Harm: a story finished while its issues stay open for good, and a save the screen does not show as made.

## Acceptance

- The unit tests above, seen red against today's rule; the core's, the Git store's and the screen's suites pass; a
  story with issues in Review moved to Finished on the mock board shows them in Finished.

## To be checked

- Whether a move into Finished, a measured state, needs a stage record the planner writes for each carried issue, as
  the commit check asks of a commit that moves an issue into a measured state, and whether an issue's own move to
  Finished writes one today.
