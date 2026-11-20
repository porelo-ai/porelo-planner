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
  "covers": "A command of the planner's executable carries a project's Markdown stories and open issues over to GitHub Issues: a dry run lists every item, field, dependency and rewritten reference it would write; the real run makes each item on GitHub, rewrites every old id to the new tracker reference, can go on after a stop without doubling anything, and writes the velocity seed per repository and size into project.yml; step 1 (the dry run) and step 2 (the real run) built, the acceptance in the test organization next"
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
  repository and size, as `velocity.seed.<repository>.<size>`, and nothing into `stats/`. Reading the seed and the
  median from the items is [PL177](README.md#pl177)'s.
- `project.yml` gains `tracker` and the seed; the person signs the changed file afterwards with
  `porelo project sign`, and the run's last line says so.
- Ends non-zero naming each item it could not carry; the dry run's lines are printed again with each item's new
  address.
- Red first: `CarryOverTest` over the two-repository fixture against a fake GitHub: the items made, every old id in
  the issue lists, dependencies and bodies rewritten to the new references, the comments in order, the sums; a run
  stopped after the first pass and run again with its map makes nothing twice; a project already holding items and
  no map is refused; the seed written into `project.yml` and nothing under `stats/`.

### Step 2, built

- `porelo-planner carry-over --project <dir> --to <owner>/<number> [--dry-run] [--seed <csv>] [--state <file>]`.
  `CarryOver` in `store/github` runs the two passes; it writes through `ProjectWrites` (making an issue, finding
  the person's issues made since a time, adding them to the project, field values, sub-issues, the body, the
  comments), the same calls a new item's save makes, `ProjectQueries` (the project's fields and items) and
  `CarryOverCalls`, which holds only what no other write needs: an issue's body, its sub-issues and blockers read,
  and `addBlockedBy`. The map is `CarryOverMap`, kept by the app's `CarryOverState`.
- **Before any call to GitHub** the command reads the checkouts, refuses a `project.yml` with changes not committed
  (or not tracked, or in a checkout that is no repository), refuses a `project.yml` naming a tracker or, with a
  seed, a `velocity` already, checks the seed, and checks that every repository stands at
  `github.com/<target owner>/<its name>`, since the GitHub store writes to a repository by the name
  `project.yml` gives it, named by an address a tracked project opens with: the tracked project's own rule
  (`ProjectFile.home`), so `https://` or `<user>@github.com:<owner>/<name>`, never `ssh://`. A dry run checks the
  addresses too.
- **Before anything is made** the run plans the whole carry-over with every reference as
  `<repository>#999999999` and as the first pass writes it, and stops when a body, its `## Also needs` included, is
  longer than GitHub takes; a plan that is not whole is not carried at all. It reads the project's fields and
  refuses one that lacks a field, its kind or an option of `Priority`, `Size`, `Risk` or `Worker`; refuses a
  project holding an item without a map, and with a map any item the map does not hold; then sets the `Status`
  options to the workflow's states through `StatusOptionsWriter`.
- **First pass**: an item's body is its text with every carried reference written as `<repository>#(carried)` and
  without `## Also needs`; no old id stands in it. Its fields leave out `Id`, and a story whose state is not given
  leaves out `Status`. A story whose new issue lacks the label `story` stops the run.
- **Second pass**: the body is written anew from the Markdown text with GitHub's references and `## Also needs`,
  only while GitHub still holds the first pass's body; a body a person changed since is left, the item named and
  the run ends non-zero, while its sub-issues, dependencies and stage comments are written all the same. Sub-issues, dependencies and comments that already stand are
  not added again; a stage comment is found by its key, `carry:<stage>:<n>`, which names no old id.
- **The map**: one entry per old id with its repository, the time its making began, its progress (`pending`,
  `created`, `on_project`, `fields_set`, `pass2_done`), number, node id, database id and project item. An item is
  written `pending` before it is made; a run again looks for an issue in its repository made since that time (less
  ten minutes against a clock's skew) with its title and first body: one is taken, none is made again, more stop
  the run naming them. The file is `carry-over/<owner>-<number>.json` under the planner's state directory by
  default, the account's alone (`PrivateFiles`), replaced whole on every save, held under a lock file beside it,
  and names the own checkout's real path and the target; a map of another checkout or target, or one open to
  others, is refused.
- **`project.yml`** is written only after the second pass ended whole: `tracker` and, with `--seed`, the seed are
  added below what the person wrote, its SHA-256 recorded in the map first, so a run again after a stop between the
  write and the map's removal takes the change as its own. Nothing is committed; the last line says to review it,
  sign it with `porelo project sign` and commit it, and the line before says the map was removed.
- Every line the command says on the error stream is taken through the token's removal and the Git store's removal
  of secrets, and every failure, an unforeseen one too, ends it non-zero without a body or a comment in its words.
- Not built: an old id written as plain text, not as a link to an index row, is left as it stands, as step 1's
  rewrite leaves it. The measured run against GitHub's secondary rate limits stays open below.
- Tests: `CarryOverTest` (GitHub in memory: every write cut once, with and without GitHub taking it, and run again
  to the same end; a project with items and no map; an item outside the map; a pending item found twice; a story
  without its label; a project lacking an option; a body a person changed; no old id and no token anywhere) and
  `CarryOverRunTest` (the command over the fixture's two Git checkouts: `project.yml` written and not committed, a
  stop and a run again, a written `project.yml` taken as the run's own, changes not committed, a tracker without a
  map, the seed's shapes, a repository of another owner, a map of another checkout, a map open to others).

### Step 1, built

- Today the executable's `issues` and `stories` commands read the Markdown store through `store/git` (`IssueIndex`,
  `StoriesIndex`, `IssueLinks`); the Git store's `refresh()` gives every story and issue with its text.
- `porelo-planner carry-over --project <dir> --to <owner>/<number> --dry-run`: reads the board from the checkouts
  and plans each item with [PL164](README.md#pl164)'s mapping, without the field `Id`; the real run writes through
  `CarryOver`, not `GitHubStore`.
- Only open issues, those with a file, are carried; Finished and Rejected rows of the indexes are not. No old id
  goes to GitHub, and no Porelo id is given after the cut-over, so no number floor is kept.
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
