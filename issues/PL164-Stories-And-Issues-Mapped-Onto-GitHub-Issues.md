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
  "covers": "Every field the planner reads or writes of a story or an issue has one place in GitHub Issues: the story an issue in the project's own repository, its issues sub-issues in their repositories, state, priority, size, risk, worker and the old id as fields of one GitHub project, blockedBy as issue dependencies, the measured sums as number fields, the analysis result and each stage's footprint in a fixed block of the body, new ids continuing each prefix's numbers; written down as a page and as one mapping class tested both ways"
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
  - One GitHub project (Projects v2) per Porelo project holds the fields: the project's own `Status` (single
    select, its options replaced by the workflow's states in order, so GitHub's board columns are the workflow's),
    `Priority` (Highest..Lowest), `Size` (XS..XL), `Risk` (harm, ordinary, small), `Worker`
    (agent, manual), `Id` (text, the Porelo id), `Rank` (number). The title is the GitHub title without the id;
    `covers` is the first paragraph of the body.
  - `blockedBy` is GitHub's issue dependency ("blocked by"), across repositories.
  - The measured sums are number fields of the project, so the board query returns them and a view filters and
    sorts by them: `Estimate` (minutes, the last estimate), `Minutes` (wall minutes of every stage), `VM minutes`,
    `Tokens` (new tokens of every model: input, output, cache write) and `Stages` (how many). The velocity reads
    `Minutes` by `Size`; nothing else on the board reads a number.
  - What has no field stands in one fixed block at the end of the body: the analysis result (plan, why this size,
    to be checked) and the footprint, one entry per stage (actor, model, effort, start, end, minutes, VM minutes,
    tokens by model and kind, as a stage record holds them today). The block sits between `<!-- porelo:begin -->`
    and `<!-- porelo:end -->`, as JSON in a fenced block; the person's own text above it is never touched. `stats/`
    and the rule that a measured state needs a stage record do not carry over.
  - The block is the truth and the number fields are its sums; only the planner writes either, both in one save.
    Where a field disagrees with its block's sum (a hand edit on GitHub), the board shows the block's sum, names
    the issue as a fault, and the next save of that issue writes the field again.
  - Measured in the test organization with a fine-grained token (Issues, Projects read and write): a project holds
    at most 50 fields, 13 of them GitHub's own; this mapping adds 11, so 24 of 50 are used. A number field filters
    on the server (`items(query: "Minutes:>10")` gave exactly the matching item, as a view's filter does). The API
    sorts items only by their position, and a view's sort cannot be set through the API (its input takes name,
    layout, filter and visible fields only), so the planner sorts by `Rank` itself and a person sets a view's
    sort by hand. Organization-level issue fields and issue types exist in the GraphQL schema
    (`Organization.issueFields`, `Issue.issueFieldValues`, `setIssueFieldValue`), but the token got 403 even to
    read them; they are not used, since project fields carry everything the board needs.
  - An issue made after the cut-over gets the next number of its prefix: one above the highest of the `Id` of every
    item on the GitHub project, closed ones included, and the prefix's floor in `project.yml` (`issue_floor`, per
    prefix, the highest number ever used before the cut-over, written by the carry-over, since Finished and
    Rejected rows are not carried). The order within a priority is the number field `Rank`, written by the planner.
  - A closed state (Finished, Rejected) also closes the GitHub issue (`completed`, `not_planned`); reopening sets
    the state back.
- Code: a new module `planner/store/github` with `IssueMapping` (GitHub's answer as plain records in, `Story` and
  `Issue` out, and back) and `PoreloBlock` (parse and write the fixed block, bounded, refusing a second block).
  No HTTP here.
- Red first: a round-trip test over a fixture of GitHub answers (a story with sub-issues in two repositories, a
  Porelo sub-issue, a dependency across repositories, a closed one) that reads `Story` and `Issue` and writes back
  the same fields; a number field set by hand to another sum gives the block's sum and a fault; the next id of a prefix whose floor lies above its highest open `Id`; a body whose person-written
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
- That the project's own `Status` options can be replaced by the workflow's states through the API
  (`updateProjectV2Field` with single-select options), keeping the option ids of states already set.
- The longest analysis result and footprint the body can carry (GitHub's body limit, 65536 characters) against
  today's longest plan and `stats/` file.
