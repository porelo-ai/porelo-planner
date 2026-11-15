# PL148 — A Needs You Row Says Its Reason In Words

```json
{
  "$schema": "issue-1.json",
  "id": "PL148",
  "title": "A Needs You Row Says Its Reason In Words",
  "state": "Review",
  "size": "XS",
  "priority": "High",
  "rank": 68,
  "blockedBy": [],
  "covers": "A row of the screen's Needs you says its reason in words a person can act on, once, and never shows the reason's wire code such as SAVE_REFUSED or STOPPED, on an issue's row or a story's"
}
```

**Why this size.** XS - one widget of the screen package and its test; no contract.

**What must be true.** A person who opens Needs you reads why each story or issue needs them in a sentence, not a
capitals code above it or in its place.

## Plan

- Today `StoryWorkAttentionView` (`screen/lib/src/ui/story_work_attention.dart`) draws
  `Text(planningShown(row.reason))` for every reason that needs attention, so the wire code ("SAVE_REFUSED", "STOPPED")
  stands above the sentence `_taskEnd` gives on an issue's row, and alone on a story's row, which has no `_taskEnd`.
- That line says `planningReasonWords(row.reason)` (`screen/lib/src/run_attention.dart`) where no `_taskEnd` says the
  reason, and nothing where one does; the code is at most a tooltip on the words. A reason the screen has no words for
  says "it needs you" rather than its code.
- What else the planner sends in capitals: an issue's `reason` and a pass-over's reason are codes from fixed lists in
  the OpenAPI description, and a finding's `severity` is `BLOCKING` or `NOTE`, also a code, to be said in words where
  shown; a task end's `why` and `answer` are the agent's own words, never a code, and are shown as given.
- Red first, in the screen's attention tests: a story's row and an issue's row with `SAVE_REFUSED` show "saving its
  findings was refused" once and no text equal to the code. Harm: a person who cannot tell what to do from the row.

## Acceptance

- The test above, seen red against today's row; the screen's suite passes.
