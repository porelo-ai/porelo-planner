# PL164 — Stories and Issues Mapped Onto GitHub Issues

```json
{
  "$schema": "issue-1.json",
  "id": "PL164",
  "title": "Stories and Issues Mapped Onto GitHub Issues",
  "state": "Analyzed",
  "size": "M",
  "priority": "High",
  "rank": 80,
  "blockedBy": [],
  "covers": "Every field the planner reads or writes of a story or an issue has one place in GitHub Issues: the story an issue in the project's own repository, its issues sub-issues in their repositories, state, priority, size, risk, worker and the old id as fields of one GitHub project, blockedBy as issue dependencies, plan and stage records in a fixed block of the body; written down as a page and as one mapping class tested both ways"
}
```

**Why this size.** M, not S: one contract that other repositories read (the agents' rules file issues on GitHub by
it, the window shows what it yields), a page in `doc/`, and one class with its round-trip tests in a new module. No
GitHub call yet.

**What must be true.** A person who opens a project's issues on GitHub finds every story and issue the planner shows,
with the same state, priority, size, risk, worker and waits, and the planner reads back exactly what it wrote.

## Plan

- Today a story is `stories/STnn-*.md` in the project's repository (`story-2.json` head: state, priority, size,
  issues), an issue `issues/<PREFIX>nn-*.md` (`issue-1.json` head: state, size, priority, rank, blockedBy, covers,
  worker, risk); the store gives them as `Story` and `Issue` (`planner/store/api`). The ids follow `Ids`
  (`ST[0-9]{1,5}`, `[A-Z]{1,4}[0-9]{1,5}(\.[0-9]{1,3})?`).
- The mapping, as a page `doc/github-issues.md`:
  - A story is a GitHub issue in the project's own repository, labeled `story`; its issues are its sub-issues, in
    their own repositories, in the story's order. A Porelo sub-issue (`PL12.1`) is a sub-issue of its issue.
  - One GitHub project (Projects v2) per Porelo project holds the fields: `State` (single select, the workflow's
    states in order), `Priority` (Highest..Lowest), `Size` (XS..XL), `Risk` (harm, ordinary, small), `Worker`
    (agent, manual), `Id` (text, the Porelo id), `Rank` (number). The title is the GitHub title without the id;
    `covers` is the first paragraph of the body.
  - `blockedBy` is GitHub's issue dependency ("blocked by"), across repositories.
  - What has no field - the plan, why this size, to be checked, the stage records of a measured state - stands in
    one fixed block at the end of the body, between `<!-- porelo:begin -->` and `<!-- porelo:end -->`, as JSON in a
    fenced block; the person's own text above it is never touched.
  - A closed state (Finished, Rejected) also closes the GitHub issue (`completed`, `not_planned`); reopening sets
    the state back.
- Code: a new module `planner/store/github` with `IssueMapping` (GitHub's answer as plain records in, `Story` and
  `Issue` out, and back) and `PoreloBlock` (parse and write the fixed block, bounded, refusing a second block).
  No HTTP here.
- Red first: a round-trip test over a fixture of GitHub answers (a story with sub-issues in two repositories, a
  Porelo sub-issue, a dependency across repositories, a closed one) that reads `Story` and `Issue` and writes back
  the same fields; a body whose person-written text holds `<!-- porelo:begin -->` in a code span stays intact.

## Acceptance

- The page names a place for every head field of `story-2.json` and `issue-1.json`, and the test fails for a field
  dropped from the mapping.
- A fixed block that is broken or present twice gives a board fault naming the issue, never a crash or a lost text.

## Open questions

- The id of an issue made after the cut-over: the next number of its prefix (the planner counts above the highest
  `Id` on the board, so ids stay continuous with the carried-over ones), or the prefix with GitHub's own issue
  number (never collides between two persons, but jumps and can clash with a carried-over id).
- The order within a priority: a `Rank` number field the planner writes, or the GitHub project view's manual order
  (a person can drag it, but the planner reads it only through the view).

## To be checked

- That GitHub allows a sub-issue in another repository of the same owner, and its limits (sub-issues per parent,
  nesting depth), in the test organization.
- That issue dependencies are readable and writable through GraphQL with a fine-grained token, and across
  repositories.
- The longest stage record the body can carry (GitHub's body limit, 65536 characters) against today's longest plan.
