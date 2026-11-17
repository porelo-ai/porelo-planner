# PL160 — A Stage Result's Plan Headings Lowered, Not Refused

```json
{
  "$schema": "issue-1.json",
  "id": "PL160",
  "title": "A Stage Result's Plan Headings Lowered, Not Refused",
  "state": "Implementing",
  "size": "S",
  "priority": "Highest",
  "rank": 1,
  "blockedBy": [],
  "covers": "A stage's order tells the agent that its plan holds no first- or second-level heading and closes every code block, and a handed-in plan's first- and second-level headings outside code blocks are lowered to third-level ones before the result is checked and saved, so an analysis is no longer lost for a heading; a code block left open is still refused"
}
```

**Why this size.** S: the order's text in `StageOrder.instructions`, and one rewrite of the plan in `HandedResults`
before `StageResult` checks it. Tests in `StageOrderTest` and `HandedResultsTest`.

**What must be true.** An analysis whose plan opens with a heading such as `## CC81: ...` is saved, with the
heading kept as a third-level one, and the agent is told the rule before it writes. The planner refuses only a plan it
cannot repair.

## Found

On 2026-11-17, in a live test on a rented host. The analysis of a story with one issue ran to its end and handed in
`analysis.json`. The window said: "The planner did not save the analysis.json of porelo_claude: The result does not
follow the result format. issues: CC81: the plan holds a heading or a code fence that would open a section of its own
or leave a code block open." The plan's first line was `## CC81: audit the documentation against the code`. Its
other headings were `###` and are allowed. The whole run's work was lost.

## Plan

- Today: `StageOrder.instructions` (`planner/core`) asks only for "plan (Markdown, the base design read in today's
  code)". It does not name the rule that `StageResult.checkPlan` (`planner/store/api`) enforces: no `#` or `##`
  heading outside a code block, any indentation, and no fence other than an opening of three backticks with a plain
  language name, closed by three backticks alone. `HandedResults.entry` and `plan` check length and characters, then
  `new StageResult(...)` throws, and the throw becomes "does not follow the result format".
- Change 1: the order's text adds that the plan holds no first- or second-level heading (it goes under the issue's
  own *Plan* section, so start at `###`), and that every code block opens with three backticks and closes with three
  backticks alone.
- Change 2: in `HandedResults`, before `StageResult` is built, every line outside a code block whose stripped text
  matches `#{1,2}(\s.*)?` becomes `### ` plus its text, with the indentation dropped. The same fence logic as
  `checkPlan` decides what is inside a block. Fences are not repaired: a `~~~` fence or a block left open is still
  refused, with the same message.
- Red first: a `HandedResultsTest` with a plan whose first line is `## CC81: x` and that holds a `### Steps`. Today
  it is refused; after the change it is saved with `### CC81: x`. A `StageOrderTest` checks that the order's text
  names the heading rule.

## Acceptance

- An analysis whose plan holds `#` or `##` headings outside code blocks is saved, with those headings as `###`.
- A `##` line inside a code block is kept as it is.
- A plan with a code block left open is still refused.
- An analysis order's text names the heading and code-block rule.
- The tests above fail without the change.
