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
  "covers": "Every field the planner reads or writes of a story or an issue has one place in GitHub Issues: the story an issue in the project's own repository, its issues sub-issues in their repositories, state, priority, size, risk, worker and the old id as fields of one GitHub project, blockedBy as issue dependencies, analysis result and footprint in a fixed block of the body, new ids continuing each prefix's numbers; written down as a page and as one mapping class tested both ways"
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
  - What has no field stands in one fixed block at the end of the body: the analysis result (plan, why this size,
    to be checked) and the footprint, one entry per stage (actor, model, effort, start, end, minutes, tokens, as a
    stage record holds them today); the minutes a size took are its footprints' median. The block sits between
    `<!-- porelo:begin -->` and `<!-- porelo:end -->`, as JSON in a fenced block; the person's own text above it is never touched. `stats/` and the rule that a measured state needs
    a stage record do not carry over.
  - An issue made after the cut-over gets the next number of its prefix: one above the highest of the `Id` of every
    item on the GitHub project, closed ones included, and the prefix's floor in `project.yml` (`issueFloor`, per
    prefix, the highest number ever used before the cut-over, written by the carry-over, since Finished and
    Rejected rows are not carried). The order within a priority is the number field `Rank`, written by the planner.
  - A closed state (Finished, Rejected) also closes the GitHub issue (`completed`, `not_planned`); reopening sets
    the state back.
- Code: a new module `planner/store/github` with `IssueMapping` (GitHub's answer as plain records in, `Story` and
  `Issue` out, and back) and `PoreloBlock` (parse and write the fixed block, bounded, refusing a second block).
  No HTTP here.
- Red first: a round-trip test over a fixture of GitHub answers (a story with sub-issues in two repositories, a
  Porelo sub-issue, a dependency across repositories, a closed one) that reads `Story` and `Issue` and writes back
  the same fields; the next id of a prefix whose floor lies above its highest open `Id`; a body whose person-written
  text holds `<!-- porelo:begin -->` in a code span stays intact.

## Acceptance

- The page names a place for every head field of `story-2.json` and `issue-1.json`, and the test fails for a field
  dropped from the mapping.
- A fixed block that is broken or present twice gives a board fault naming the issue, never a crash or a lost text.

## To be checked

- That GitHub allows a sub-issue in another repository of the same owner, and its limits (sub-issues per parent,
  nesting depth), in the test organization.
- That issue dependencies are readable and writable through GraphQL with a fine-grained token, and across
  repositories.
- The longest analysis result and footprint the body can carry (GitHub's body limit, 65536 characters) against
  today's longest plan and `stats/` file.
