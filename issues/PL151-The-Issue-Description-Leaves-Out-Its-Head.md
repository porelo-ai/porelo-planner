# PL151 — The Issue Description Leaves Out Its Head

```json
{
  "$schema": "issue-1.json",
  "id": "PL151",
  "title": "The Issue Description Leaves Out Its Head",
  "state": "Review",
  "size": "XS",
  "priority": "High",
  "rank": 71,
  "blockedBy": [],
  "covers": "The issue page's Description shows the issue's text without its heading, its JSON head and the What must be true the page already shows above it"
}
```

**Why this size.** XS - one function of the screen package's issue page and its test; no contract.

**What must be true.** A person who opens an issue reads its fields above and its text below once each, with no JSON
block and no What must be true twice.

## Plan

- Seen in a live run on an issue page: the Description showed the issue's JSON head as a code block, and What must be
  true both as its own part and again inside the Description. The planner answers an issue's text as its whole file;
  `planningWithoutHeading` (`screen/lib/src/ui/planning_issue_view.dart`) leaves out only the `# <id> - <title>`
  heading.
- For Markdown, the Description also leaves out a fenced JSON block standing right below the heading, and the paragraph
  that opens with `**What must be true.**` when What must be true is shown above; everything else stays as written.
- Red first, in the screen's issue page tests: a Markdown text with heading, head, What must be true and a plan shows
  neither the head nor What must be true in the Description, and the plan. Harm: none beyond reading; tested since the
  removal must not drop the person's own text.

## Acceptance

- The test above, seen red against today's page; the screen's suite passes.
