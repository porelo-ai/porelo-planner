# PL164 — Stories and Issues Mapped Onto GitHub Issues

```json
{
  "$schema": "issue-1.json",
  "id": "PL164",
  "title": "Stories and Issues Mapped Onto GitHub Issues",
  "state": "Implementing",
  "size": "M",
  "priority": "High",
  "rank": 80,
  "blockedBy": [],
  "covers": "Every field the planner reads or writes of a story or an issue has one place in GitHub Issues: the story an issue in the project's own repository, its issues sub-issues in their repositories, state, priority, size, risk, worker and the old id as fields of one GitHub project, blockedBy as issue dependencies, the measured sums as number fields, the body holding what is true now and one comment per stage holding the course in time, new ids continuing each prefix's numbers; written down as a page and as one mapping class tested both ways"
}
```

**Why this size.** M, not S: one contract that other repositories read (the agents' rules file issues on GitHub by
it, the window shows what it yields), a page in `doc/`, and three small classes with their round-trip tests in a new
module: the mapping, the body's sections and the stage comment. No GitHub call yet.

**What must be true.** A person who opens a project's issues on GitHub finds every story and issue the planner shows,
with the same state, priority, size, risk, worker and waits, reads in its body what is true now and in its comments
how it got there, and the planner reads back exactly what it wrote.

## Plan

- Today a story is `stories/STnn-*.md` in the project's repository (`story-2.json` head: state, priority, size,
  issues), an issue `issues/<PREFIX>nn-*.md` (`issue-1.json` head: state, size, priority, rank, blockedBy, covers,
  worker, risk), and each stage a line in `stats/<id>.jsonl` (actor, model, effort, start, end, `wall_min`,
  `vm_min`, tokens). The stage command sets `wall_min` to end minus start exactly (`StageCommand`); a pause is a
  stage of its own (`wait`, with its reason), so a stage's start is its end minus its minutes. The store gives
  `Story` and `Issue` (`planner/store/api`); the ids follow `Ids`.
- The mapping, as a page `doc/github-issues.md`:
  - A story is a GitHub issue in the project's own repository, labeled `story`; its issues are its sub-issues, in
    their own repositories, in the story's order. A Porelo sub-issue (`PL12.1`) is a sub-issue of its issue.
    `blockedBy` is GitHub's issue dependency ("blocked by"), across repositories.
  - **The body holds what is true now**, as today's sections: `covers` as its first paragraph, *What must be
    true*, *Plan*, *Why this size*, *Acceptance*, *To be checked*. A stage result overwrites the sections it
    names and leaves the others. No footprint, no times and no stage history stand in the body.
  - **The comments hold the course in time**: each stage is one comment, written by the planner with the person's
    access, so GitHub shows the person as its author. It starts with the line `<!-- porelo:stage -->` and holds
    the stage's result and a fenced JSON block: stage, result, actor, model, effort, minutes, VM minutes, tokens
    by model and kind. Its end is the comment's `createdAt`, its start that end minus its minutes; neither is
    stored; GitHub sets `createdAt` to the second of writing, which is precise enough. A person's move is no comment: GitHub's timeline gives each `Status` change with its
    time, its actor and the state before and after.
  - **Project fields hold what is filtered and sorted**: the project's own `Status` (its options replaced by the
    workflow's states in order, so GitHub's board columns are the workflow's), `Priority` (Highest..Lowest),
    `Size` (XS..XL), `Risk`, `Worker`, `Id` (text, the Porelo id), `Rank` (number), and the sums of the stage
    comments as number fields: `Estimate` (minutes, the last estimate), `Minutes`, `VM minutes`, `Tokens` (new
    tokens: input, output, cache write) and `Stages`. The velocity reads `Minutes` by `Size`.
  - The stage comments are the truth and the number fields their sums; only the planner writes both, in one save.
    A field that disagrees with its comments' sum (a hand edit on GitHub) is shown as the sum, the issue is named
    as a fault, and the next save of that issue writes the field again. `stats/` and the rule that a measured
    state needs a stage record do not carry over.
  - An issue made after the cut-over gets the next number of its prefix: one above the highest of the `Id` of every
    item on the GitHub project, closed ones included, and the prefix's floor in `project.yml` (`issue_floor`, per
    prefix, the highest number ever used before the cut-over, written by the carry-over, since Finished and
    Rejected rows are not carried). The order within a priority is `Rank`, sorted by the planner.
  - A closed state (Finished, Rejected) also closes the GitHub issue (`completed`, `not_planned`); reopening sets
    the state back.
- Measured in the test organization with a fine-grained token (Issues, Projects read and write), throw-away
  repository and project deleted after each run:
  - A project holds at most 50 fields, 13 of them GitHub's own; this mapping adds 11, so 24 of 50 are used.
  - A number field filters on the server: `items(query: "N1:>10")` over 30, 5 and 12 gave only 30. The API orders
    items only by position, and a view made through the API takes no sort, so the planner sorts by `Rank` itself
    and a person sets a view's sort by hand.
  - Two `Status` changes appeared in the issue's timeline as `ProjectV2ItemStatusChangedEvent` with `createdAt`,
    `previousStatus`, `status` and `actor`, readable over GraphQL, about two seconds after the write. A change of a
    custom single-select field (`Priority`) appeared in no timeline item.
  - A comment's `createdAt` was the second of the write (written 09:44:44.258 to 09:44:45.366, `createdAt`
    09:44:45); a `created_at` sent with it was ignored.
  - Replacing `Status`'s options gives every option a new id, a kept name included (`Todo` f75ad846 became
    97fa4091), so the planner reads the ids again after setting them up.
  - Organization issue fields and issue types exist in the schema (`Organization.issueFields`,
    `Issue.issueFieldValues`, `setIssueFieldValue`), but the token got 403 even to read them; not used.
- Code, in two steps. **Step 1, the types (about 30 minutes, pushed first):** the module `planner/store/github`
  (its `pom.xml`, listed in `planner/store/pom.xml`) with `package-info.java`, the records `ProjectRef`
  (owner, number), `ItemView` (an item as the mapping takes it: node id, repository, number, title, body, state,
  fields by name, parent, sub-issues, blocked-by, `updatedAt`), `FieldNames` (the field names and kinds),
  `StageEntry` (one stage comment's values), and the interfaces the other issues implement: `BoardReader` (the
  board from the project), `BoardWriter` (the five writes), `ChangeProbe` (unchanged since the last read, with a
  default that answers changed), `TokenSource` (the token for an address), `AccessRefusals` (a forge refusal
  turned into a store refusal, default `CredentialRefused`); and `GitHubStore`, which implements `PlanningStore`
  by delegating to them. **Step 2, the mapping:** `IssueMapping`, `IssueMapping` (GitHub's answer as plain records in, `Story` and
  `Issue` out, and back), `BodySections` (read and overwrite the named sections, leaving the others byte for
  byte) and `StageComment` (write and read the stage comment, bounded, its end from `createdAt`). No
  HTTP here.
- Red first: a round-trip test over a fixture of GitHub answers (a story with sub-issues in two repositories, a
  Porelo sub-issue, a dependency across repositories, a closed one, three stage comments) that
  reads `Story` and `Issue` and writes back the same fields; a number field set by hand to another sum gives the
  comments' sum and a fault; the next id of a prefix whose floor lies above its highest open `Id`; a result that
  overwrites *Plan* leaves *Acceptance* intact.

## Plan reading

Read by agy `gemini-4-argon-high` before the build; every answer is taken:

- Hand edits on GitHub never crash the board: `IssueMapping` checks each item's `Id`, `Status`, repository, parent
  prefix, `blockedBy` targets and sub-issue cycles first and leaves a bad item out as a fault, its story then
  `issuesWhole = false`. `Issue.parent` is set only when the GitHub parent is an issue, never a story.
- `Status` is the truth: an item whose GitHub open or closed state disagrees with whether its `Status` is closed is
  shown with its `Status` and named as a fault.
- A story whose `Status` is empty follows its issues (`stateGiven = false`); a set `Status` is given. "Follow the
  issues again" clears it.
- `doc/github-issues.md` places every field of `story-2.json`, `issue-1.json` and a result: a story's *Gain*,
  *Test* and *Tested*, an issue's `task`, `role`, `severity` and `kind` as body sections, *Open questions* and
  *Findings* too. A result overwrites only the sections its kind writes (an estimate never *To be checked*);
  headings inside ``` and ~~~ fences are not sections.
- An issue in two stories: GitHub allows one parent, so the first story is its parent and every other story lists
  it in an *Also needs* section of its body, read back into `Story.issues`.
- A stage comment carries a key (the task and the stage's ordinal); a retry finds the key and writes no second
  comment, and the sums are always computed from the comments without doubles. Its JSON block stands right after
  the marker line; a negative, non-finite or overflowing number or an unknown field makes it a broken comment,
  left out of the sums as a fault. `Minutes` and `Tokens` sum as `StageRecords` sums today: closed agent stages of
  the measured kinds only, `wait` and others shown but not summed.
- Ids are counted over every item, archived ones included, and `issue_floor` holds `ST` too.
- `TokenSource` takes only an `https://` origin without user, path or query; every message and fault from
  `store/github` is scrubbed of the token, as `GitRunner` scrubs Git's.
- Step 1 grows: `ItemView` gains the item's and the issue's node ids, `labels`, `comments` (`CommentView`: body,
  `createdAt`), `link` and `stateReason`; a `ProjectConfig` record (forge address, `ProjectRef`, workflow, work
  rules, repositories, `issue_floor`, field and option ids); `BoardReader` answers a `Snapshot` (the board, its
  items by id, the config), which `GitHubStore` hands to `BoardWriter`, whose five signatures are fixed in step 1;
  a `StageMetrics` source (default: none) gives `saveResult` the stage's numbers from Porelo's record of the task;
  `GitHubStore` holds the bounded project lock (`Busy`), the `retire()` refusal, `storyState`, `textFormat()` and
  defaults for every collaborator, so the builders of the other issues never edit it.

## A tracker of its own

The tracker may later differ from the forge (an issue tracker beside another code host), so what a Porelo issue
is stays apart from how one tracker keeps it:

- `store/api` holds the model and names nothing of any tracker: `Board`, `Story` (with its priority and the
  board's order), `Issue` (id, state, size, blocked-by, parent, text, worker, risk), `StageResult`, and a stage's figures, `StageEntry`, with `StageMetrics`, their source in Porelo's record of the
  task. `TokenSource` is there too, since every hosted tracker takes the person's token for an https origin.
- `store/github` alone holds GitHub's names and shapes: project items and their node ids (`ItemView`), the field
  names and option ids (`FieldNames`, `ProjectConfig`), sub-issue and dependency calls, the timeline, the stage
  comment's marker, and the forge clients' calls behind `BoardReader`, `BoardWriter` and `ChangeProbe`.
- Another tracker needs a store module, a client and a mapping of its own, and none of `store/api`, `core` or the
  app changes for it.

## Order and files

Built in this order, each pushed once what it needs is published: [PL164](README.md#pl164)'s types;
[PL169](README.md#pl169)'s contract (independent, at once); then [PL165](README.md#pl165), [PL125](README.md#pl125)
followed by [PL166](README.md#pl166), and [PL169](README.md#pl169)'s behavior followed by [PL167](README.md#pl167),
side by side; then [PL168](README.md#pl168). Each builds against the forge clients' published surface with fakes of
its own.

- Step 1 owns `ProjectRef`, `ItemView`, `FieldNames`, `StageEntry`, `BoardReader`, `BoardWriter`, `ChangeProbe`,
  `TokenSource`, `AccessRefusals`, `ProjectConfig`, `Snapshot`, `CommentView`, `StageMetrics` and `GitHubStore`; step 2 `IssueMapping`, `BodySections`, `StageComment` and
  `doc/github-issues.md`. No other issue edits these files; a change they need is said in the room first.

## Acceptance

- The page names a place for every head field of `story-2.json`, `issue-1.json` and a stage record, and the test
  fails for a field dropped from the mapping.
- A stage comment that is broken gives a board fault naming the issue, never a crash, and is left out of the sums.

## To be checked

- That GitHub allows a sub-issue in another repository of the same owner, and its limits (sub-issues per parent,
  nesting depth), in the test organization.
- That issue dependencies are readable and writable through GraphQL with a fine-grained token, and across
  repositories.
- Whether replacing `Status`'s options keeps the values items already hold, since every option gets a new id; if
  not, a workflow that gains a state needs its items' states written again.
