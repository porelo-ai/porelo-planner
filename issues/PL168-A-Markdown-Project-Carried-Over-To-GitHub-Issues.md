# PL168 — A Markdown Project Carried Over to GitHub Issues

```json
{
  "$schema": "issue-1.json",
  "id": "PL168",
  "title": "A Markdown Project Carried Over to GitHub Issues",
  "state": "Backlog",
  "size": "M",
  "priority": "High",
  "rank": 84,
  "blockedBy": ["PL165", "PL166"],
  "covers": "A command of the planner's executable carries a project's Markdown stories and open issues over to GitHub Issues: a dry run lists every issue, field, dependency and rewritten reference it would write; the real run keeps each old id, can be run again without doubling anything, and says what it could not carry"
}
```

**Why this size.** M: one command in the executable over the Git store's reading and the GitHub store's writing,
the rewriting of references, and its tests; the removal of the Markdown files is each repository's own work.

**What must be true.** On the cut-over day a project's planning moves to GitHub Issues in one run that was seen
first as a dry run, and every story and issue keeps the id people know it by.

## Plan

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
  - **Step 2, the real run**, after [PL165](README.md#pl165) and [PL166](README.md#pl166): `CarryOver` writes the
    plan through `BoardWriter`, finds an item already carried by its alias on the read board, and ends non-zero on
    a failed item; owns `CarryOver` and `CarryOverTest`.

## Acceptance

- In the test organization a copy of a real project is carried over: the dry run's count equals the board's, and
  after the run the planner's board from GitHub equals the board from Markdown for every open issue, id for id and
  field for field, each item's old id standing as its alias.

## To be checked

- How long a carry-over of the real project takes against GitHub's secondary rate limits (content-creating calls
  per minute), measured on a copy in the test organization.
