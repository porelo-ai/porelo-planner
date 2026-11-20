# PL166 — Moves and Results Written to GitHub Issues

```json
{
  "$schema": "issue-1.json",
  "id": "PL166",
  "title": "Moves and Results Written to GitHub Issues",
  "state": "Backlog",
  "size": "M",
  "priority": "High",
  "rank": 82,
  "blockedBy": ["PL165"],
  "covers": "The GitHub store saves a move, a worker, a priority, a new issue, an idea and a task's stage result with the person's own GitHub access, without a commit: field changes bundled into one GraphQL mutation, a stale board refused, a partly saved batch named, and a stage result written into the sections of the issue's body it names and as one stage comment under the person's name"
}
```

**Why this size.** M, though the forge clients bring the batched mutations: five write methods of `PlanningStore`,
each with its refusals, the stale check and a partly saved batch; no contract with the window changes, since the
window calls the same operations. No id is counted: a new item is known by its GitHub number.

**What must be true.** A person who moves an issue sees it moved on GitHub at once, and a finished task's result
stands on its issue under the person's name, with no fetch, commit or push.

## Plan

- Today `GitStore.save`, `createIssue`, `createIdea` and `saveResult` write files, index them, commit and push,
  refusing with `StaleRevision`, `NotSavable`, `Busy` and `PartlySaved` (`PlanningStore`).
- `FieldsBoardWriter.save(revision, writes)`: right before the write it reads the items to be written by
  `issueBoard` and compares each one's digest with the snapshot the request was based on. The digest covers its
  field values, `Status` included, its parent, blockers, open state and body. A change by a person in between is
  `StaleRevision` naming the item, and nothing is written. The issue's `updatedAt` alone is not enough, since a
  project field changed by hand leaves it as it was. GitHub takes no condition on a write (no `If-Match` on the
  issue update or the field mutation), so a change in the second between this read and the write is not caught.
  That window is the one left, and it is named in the user page. The states, workers and priorities go through the forge clients' `setFieldValues`, one aliased
  mutation of at most 50 values (the story measured ten issues in three repositories at 6.3 s); a closed state also
  closes the issue through `updateIssue`. GitHub has no transaction: the client answers each alias's result or
  error, and what failed beside what succeeded is `PartlySaved` with the ids saved. The stale check reads the
  written items by `issueBoard`. The store holds no query text and no records of its own.
- A stage's save adds that stage's minutes, VM minutes, tokens and one stage to the number fields as read just
  before the write, in the same `setFieldValues` batch as its state. It never recomputes them from the stage
  comments, so a value a person set stays the base that later stages add to. A field without a value counts as 0.
  The stage comment is written beside, as the course of the work; the board never reads it.
- `createIssue`: `createIssues`, `addProjectItems` and `setFieldValues` (measured 4.6 s for the four calls of a
  single issue); a new issue is known by its tracker reference, GitHub's own number, and its `Id` stays empty, so
  two persons adding at once never collide. `createIdea`: the story and its issues the same way, bundled. Both
  answer the tracker reference only once [PL170](README.md#pl170) makes it an id of the window's contract; until
  then they are refused as not offered.
- `saveResult(result)`: the plan, why this size and to be checked overwrite those sections of each issue's body,
  leaving the others; the new state goes into `Status`; and one stage comment, in the form the mapping gives
  (result, actor, model, effort, minutes, VM minutes, tokens from Porelo's record of the task), is written with
  the person's token so GitHub shows the person as its author. Its `createdAt` is the stage's end. Findings go into
  the body's *Findings* section.
- No stage record is written and no measured-state rule is checked. `Size` and `Estimate` are set and `Minutes`,
  `VM minutes`, `Tokens` and `Stages` added to in the same field mutation as `Status`; the sums come from the
  fields plus the new stage. The issue's comments are read only to find whether a comment of the stage's key stands
  already, in which case neither the comment nor its figures are written again.
- Red first: `FieldsBoardWriterTest` over fakes: a move of two issues in two repositories ends in one
  mutation; a board changed meanwhile is refused; the second of two calls failing gives `PartlySaved`; a stage
  result that names *Plan* leaves *Acceptance* byte for byte and adds exactly one stage comment.

### Plan reading

- A `project.yml` pointing the tracker at another owner or host: the opener keeps a GitHub tracker on github.com
  only, and an item outside the project's owner or repositories is left out of the board, so the writer, which
  writes only items on the board, never reaches it. Tying the owner to the window's own clone URL changes how a
  project is opened and stays out of this issue.
- The token asked for by a bare origin rather than the repository's address: unchanged here; how the vault is asked
  belongs to the token source, not to the writes.
- The token asked for again to scrub a failure: the writer takes the token once per write and turns every failure
  into the store's refusal with that same token, so a write's refusal never depends on a second vault call. The
  store's own scrubbing of a read failure is unchanged.
- A `StoreException` passed on unscrubbed and a short token not scrubbed: every refusal the writer builds carries
  fixed words, and GitHub's words only after scrubbing with the token used; the length bound of the scrubber is
  unchanged.
- No revision check before a write: `save` first compares the revision the window showed with the board kept and
  refuses a difference as `StaleRevision`, before anything is read or written.
- `updatedAt` missing a change of a project field: the edit check compares each item's field values, `Status`
  included, its parent, its sub-issues, its blockers, its labels, its open state and its body, read by `issueBoard`
  right before the write; sub-issues or blockers running past the one page read count as a change. An issue's
  comments are read before that check, so no long read stands between the check and the write.
- More than 50 values, reopening, and the board kept after a partial save: values go in mutations of at most 50; a
  move out of a closed state reopens the issue, a move into one closes it, and a move between Finished and Rejected
  sets GitHub's reason, completed or not planned (a duplicate stays a duplicate under Rejected); any failure after
  a first part was written is `PartlySaved`, and the store forgets its board on every failed write, so the next
  read is GitHub's. A failure of the read after a write is `PartlySaved` in every store the opener opens, and its
  words are fixed unless they are a refusal of the store's own, so no token can stand in them.
- The order of a write's calls: each issue's body and open state first, then the field values, then the stage
  comment. A body GitHub refuses leaves the state and the sums unwritten, so the result can be handed in again; a
  field mutation failing after the body leaves the issue in its state, and the result handed in again finds its
  body written and adds the sums once. A comment failing after the fields is `PartlySaved` with the sums added once;
  the result handed in again is refused, as the issue left the state the stage completes, so the course lacks that
  comment until a person adds it. Writing the comment before the fields was weighed and not taken: a field mutation
  failing after it would make the next hand-in find the key and never add the sums.
- A story whose `Status` is cleared to follow its issues again while its GitHub issue is closed: not changed here. A
  story that follows its issues is never closed or reopened by the planner, as the last of its issues finishing
  leaves it open too, and the board names no fault for it; whether such a story's open state follows its derived
  state is a decision of its own, not part of the writes.
- `PartlySaved` carrying item ids: it carries repository names, as the store API defines it.
- Creating an issue or an idea twice on a retry: both stay refused as not offered until tracker references are the
  window's ids, so no issue is created yet.
- `saveResult` over a person's move, sums counted twice, several issues: each issue must still stand in the state
  the stage completes and move to an open state, or nothing is written; the edit check runs over the fresh read too.
  A stage whose key stands among the issue's comments is neither commented nor added again. A result for several
  issues writes their states, sizes and bodies and no stage comment, as no one stage's figures belong to one issue.
  Findings go into the body's *Findings* section. The worker is not checked, as the Git store does not check it.
- `setFieldOptions` on a write: the writer's calls hold no way to set options, and a field or option the project
  lacks is `NotSavable` before any call.
- Fields written on a read path: the reader's calls hold no write; unchanged.
- A rate limit read as a missing access: the refusals already keep a rate-limited 403 apart from a missing access;
  unchanged.
- A stage saved over the board read just before it rather than the board the store held: a stage runs for
  minutes and the window's reads renew the board kept meanwhile, so that board marks no stage's start, and a
  refusal as changed would be handed in again on a board read anew and written over the same edit. The Git store
  saves a result on the remotes as they stand, too. A stage writes only the sections its kind names, so a person's
  words elsewhere in the body stay; a person's edit of a section the stage writes, made while the stage ran, is
  replaced by the stage's result.
- Reads on every write: a move reads only the items it writes by `issueBoard` before writing; a stage's save reads
  the whole board first, as its state check needs the board as it stands, and an issue's comments; the full read
  after a write is kept, as it gives the window the board it answers. Comments, archived items and the page bound
  of the board read stay with the board read.
- A comment read failing named the project's own repository: it names the issue's repository. A page of comments
  longer than asked for is taken as it comes: it only gives more comments to search, and the page count bounds the
  calls.
- A body section swallowed by a fence or heading the section reader does not see: tracked in
  [PL171](README.md#pl171).
- Tests that stay green without their guard: each guard named here has a test in `FieldsBoardWriterTest`,
  `GitHubStoreTest` or `GitHubStoreOpenerTest` that was seen red with its guard broken.

## Order and files

Built in this order, each pushed once what it needs is published: [PL164](README.md#pl164)'s types;
[PL169](README.md#pl169)'s contract (independent, at once); then [PL165](README.md#pl165), [PL125](README.md#pl125)
followed by [PL166](README.md#pl166), and [PL169](README.md#pl169)'s behavior followed by [PL167](README.md#pl167),
side by side; then [PL168](README.md#pl168). Each builds against the forge clients' published surface with fakes of
its own.

- Owns `store/github`: `FieldsBoardWriter` (implements `BoardWriter`). Test: `FieldsBoardWriterTest`. Built against
  [PL164](README.md#pl164)'s types and a fake reader, so it does not wait on [PL165](README.md#pl165).

## Acceptance

- In the test organization a move made in the window shows on GitHub within the second after the answer, and no
  repository gains a commit.
- A finished task's result shows on its issue as a comment by the person.

## To be checked

- Measured by the forge clients' recorder: GitHub saves the parts before and after a refused one, and a batch that
  fails naming no part is unknown, so the store reads the written items by `issueBoard` before it answers or retries;
  the `PartlySaved` answer names what GitHub really saved.
- Measured: a project's status changes list only an item's first `Status`, not a later one written through the API.
  Whether a move written by the planner shows in GitHub's timeline later is still being measured; if it does not,
  a move's course is not kept on GitHub, and the plan's claim that the timeline shows each move is narrowed to
  moves a person makes on GitHub.
