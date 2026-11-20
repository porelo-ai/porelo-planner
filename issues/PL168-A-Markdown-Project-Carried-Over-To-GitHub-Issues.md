# PL168 — A Markdown Project Carried Over to GitHub Issues

```json
{
  "$schema": "issue-1.json",
  "id": "PL168",
  "title": "A Markdown Project Carried Over to GitHub Issues",
  "state": "Implementing",
  "size": "L",
  "priority": "High",
  "rank": 84,
  "blockedBy": ["PL165", "PL166"],
  "covers": "A command of the planner's executable carries a project's Markdown stories and open issues over to GitHub Issues: a dry run lists every item, field, dependency and rewritten reference it would write; the real run makes each item on GitHub, rewrites every old id to the new tracker reference, can go on after a stop without doubling anything, and writes the velocity seed per repository and size into project.yml; step 1 (the dry run) built, step 2 (the real run) next"
}
```

**Why this size.** L, not M: besides the command over the Git store's reading, step 2 makes every item on GitHub
in an order that lets each old id be rewritten to a reference not yet known when the text was read, carries the
stage comments and sums, goes on after a stop without an id on GitHub to find what it made, and seeds the velocity;
the removal of the Markdown files is each repository's own work.

**What must be true.** On the cut-over day a project's planning moves to GitHub Issues in one run that was seen
first as a dry run, and every reference between the carried stories and issues points at the new item.

## Plan

### Step 2, the real run, as decided for the cut-over

- **No id survives on GitHub.** The run makes each open story and issue as a GitHub issue in its repository and
  keeps a map old id -> new tracker reference only while it copies. Nothing on GitHub names the old id: no `Id`
  field, no alias, no marker in a body.
- **Order**: first every item is made with its title and a body of its sections whose references are not yet
  rewritten, and added to the project with its fields and sums; the map is then whole, and a second pass rewrites
  every old id in the stories' issue lists (as sub-issues), `blockedBy` (as GitHub's dependencies) and the bodies
  to the new reference, and writes the stage comments, oldest first. An old id outside the map (finished, rejected,
  or of another project) is left as text and listed.
- **A stop and a run again**: the map is written, as it grows, to a file of the run's own under the planner's
  private state (never into a repository), with the project and the time it began; a run again with that file
  goes on from it and makes no item twice. Without the file a run refuses a project that already holds items, so
  nothing is doubled. The file is removed once the run ends whole, and its last line says so.
- **Velocity seed**: `--seed <csv>` takes a file of `repository,size,minutes` rows (the current medians, which the
  person derives from the velocity records); the run writes them into `project.yml` as the starting value per
  repository and size, in the key [PL177](README.md#pl177) agrees, and nothing into `stats/`. Reading the seed and
  the median from the items is [PL177](README.md#pl177)'s.
- `project.yml` gains `tracker` and the seed; the person signs the changed file afterwards with
  `porelo project sign`, and the run's last line says so.
- Ends non-zero naming each item it could not carry; the dry run's lines are printed again with each item's new
  address.
- Red first: `CarryOverTest` over the two-repository fixture against a fake GitHub: the items made, every old id in
  the issue lists, dependencies and bodies rewritten to the new references, the comments in order, the sums; a run
  stopped after the first pass and run again with its map makes nothing twice; a project already holding items and
  no map is refused; the seed written into `project.yml` and nothing under `stats/`.

### Step 1, built

- Today the executable's `issues` and `stories` commands read the Markdown store through `store/git` (`IssueIndex`,
  `StoriesIndex`, `IssueLinks`); the Git store's `refresh()` gives every story and issue with its text.
- `porelo-planner carry-over --project <dir> --to <owner>/<number> [--dry-run]`: reads the board from the
  checkouts, writes through `GitHubStore` ([PL166](README.md#pl166)) with [PL164](README.md#pl164)'s mapping, the old id into `Id`. A story or issue whose
  `Id` is already on the GitHub project is updated, never made again, so a run that stopped can run again.
- Only open issues, those with a file, are carried; Finished and Rejected rows of the indexes are not. Each carried
  item's old id goes into `Id` once, as its fixed alias; no Porelo id is given after the cut-over, so no number
  floor is kept. `project.yml` gains only `tracker`; in an organization's project the person signs the changed file
  afterwards with `porelo project sign`, as for any change to `project.yml`; the run's last line says so.
- An open issue's stage records (`stats/<id>.jsonl`) become its stage comments, oldest first, with their result,
  actor, model, effort, minutes, VM minutes and tokens; its number fields are their sums. GitHub sets each comment's
  `createdAt` to the carry-over's time, so the old ends are not carried. Nothing the board or the velocity reads is
  lost by it: the board reads no time, and the velocity reads `Minutes` by `Size`, both carried. What is lost is
  when each old stage happened; it stays readable in the repository's Git history of `stats/`. Its head moves into
  fields and its sections into the body.
- References in the texts are rewritten: `[PL12](README.md#pl12)` and a link to another repository's index row
  become the GitHub issue's tracker reference; a reference to a number not carried (a finished or rejected one) is listed and left as plain text, not guessed.
- The dry run prints one line per item (id, repository, state, fields, dependencies, references rewritten) and
  writes nothing; the real run prints the same lines with the GitHub address and ends non-zero if any item failed.
- Red first: `CarryOverTest` over a fixture project of two repositories with a story, a Porelo sub-issue, a
  dependency and cross links, against a fake GitHub: the dry run's lines, then the writes; a second run writes
  nothing new.

### Plan reading

The code reading of step 1 (the dry run), answered finding by finding; each one fixed was confirmed in the code first
and its test seen red.

- *Stage records read through a linked `stats` directory, and a linked directory listed as empty*: fixed. The stage
  records are read through the checkouts' own reading, and a listing refuses a directory that is a link and any link
  in it, instead of listing nothing.
- *`stories/workflow.yml` read without the checkouts' guards; a project named through a link*: fixed. The workflow
  is read through the checkouts, and the own checkout is taken at its real path, so the other repositories are found
  beside it.
- *The search for a settled issue's mentions climbs to a repository above a checkout*: fixed. Git looks for the
  repository in the checkout alone; a checkout that is no repository stops the run when it has a settled issue to
  search for. A `.git` file of a worktree stays allowed: Git then still searches the files of the checkout.
- *One directory read as two repositories; an issue under a prefix its repository does not own*: fixed. Two
  repositories at one real directory stop the run, and so does an issue of another repository whose prefix
  `project.yml` does not give that repository.
- *An open issue with an index row but no file left out unseen*: fixed. It leaves no plan.
- *A file read through a directory linked into the checkout*: fixed. A path whose directories pass through any link
  is refused.
- *A sub-issue carried while its parent is not*: fixed. The item cannot be carried, so the plan is not whole.
- *An open story without its text*: fixed. It leaves no plan.
- *Another owner's repository of the same name, and relative links out of the repository or to a page that is no
  index*: fixed. A link on the web is rewritten only when it starts with a repository's own web address and names
  one of its indexes; a relative link only when it reaches an index of the text's own repository from the text's
  directory.
- *Code spans of two backticks, longer fences, code on one line between three backticks*: fixed with the fence
  reading of `BodySections`, and code spans of any run of backticks.
- *A story's generated list kept when written with carriage returns, and a person's later line taken out*: fixed.
- *A story whose text already holds a section `Also needs`*: fixed. It cannot be carried.
- *A stage recorded twice with one `n`, and stage comments whose sums cannot be read back*: fixed. The first leaves
  no plan, the second makes the item one that cannot be carried.
- *A secret in a link that a fault names*: fixed. Every fault and failure the dry run says is taken through the Git
  store's removal of secrets. A fault still names the file and the link target, so the person can find what to mend;
  it is never a body.
- *The search and the listings untested*: fixed with tests of the listings, a linked directory, the search, and a
  settled issue whose file was deleted.
- *The refusal without `--dry-run` untested in its words*: fixed.
- Beyond the reading: a fault of the board that concerns no carried item - a settled issue still named by an index
  or by a page outside the issue files - no longer stops the dry run; it is said after the item lines under a heading
  of its own, and the run still says how many items it would carry. A link to a deleted file inside an issue file
  still stops it, since it is that issue's text.

## Order and files

Built in this order, each pushed once what it needs is published: [PL164](README.md#pl164)'s types;
[PL169](README.md#pl169)'s contract (independent, at once); then [PL165](README.md#pl165), [PL125](README.md#pl125)
followed by [PL166](README.md#pl166), and [PL169](README.md#pl169)'s behavior followed by [PL167](README.md#pl167),
side by side; then [PL168](README.md#pl168). Each builds against the forge clients' published surface with fakes of
its own.

- Owns `planner/app`: a `carry-over` command beside `WorkCommands`' `issues` and `stories`; `store/github`:
  `CarryOver` (the dry run's lines and the writes through `BoardWriter`), `ReferenceRewrite`. Test:
  `CarryOverTest`. It comes last, since it writes through [PL165](README.md#pl165) and [PL166](README.md#pl166).
- Split in two steps, so the dry run does not wait for the store's reads and writes:
  - **Step 1, the dry run**, needs only [PL164](README.md#pl164)'s types and mapping: `CarryOverPlan` reads the
    board from the checkouts through the Git store as it reads today, turns each open story and issue into what
    would be written (fields with the alias, body sections, sub-issues, dependencies, stage entries from
    `stats/<id>.jsonl`), and `ReferenceRewrite` with its list of numbers not carried; the command prints the dry
    run's lines and writes nothing. Owns `store/github`'s `CarryOverPlan` and `ReferenceRewrite`, `planner/app`'s
    `carry-over` command with `--dry-run` alone, and `CarryOverPlanTest`, `ReferenceRewriteTest`.
  - **Step 2, the real run**, after [PL165](README.md#pl165) and [PL166](README.md#pl166): `CarryOver` makes the
    items through the forge client's issue and project calls, keeps its map of old ids in its own state file,
    rewrites the references, writes the seed, and ends non-zero on a failed item; owns `CarryOver`, `CarryOverMap`
    and `CarryOverTest`. Step 1's plan names no alias any more: the `Id` field leaves its lines.

## Acceptance

- In the test organization a copy of a real project is carried over: the dry run's count equals the board's, and
  after the run the planner's board from GitHub equals the board from Markdown for every open issue, field for
  field, each old id mapped to its new reference in every issue list, dependency and text; a run stopped midway and
  run again doubles nothing.

## To be checked

- How long a carry-over of the real project takes against GitHub's secondary rate limits (content-creating calls
  per minute), measured on a copy in the test organization.
