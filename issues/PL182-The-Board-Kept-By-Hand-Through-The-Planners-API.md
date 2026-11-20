# PL182 — The Board Kept by Hand Through the Planner's API

```json
{
  "$schema": "issue-1.json",
  "id": "PL182",
  "title": "The Board Kept by Hand Through the Planner's API",
  "state": "Implementing",
  "size": "L",
  "priority": "Highest",
  "rank": 2,
  "blockedBy": ["PL170", "PL181"],
  "covers": "A person, or an agent acting for them, keeps the board through the planner's API alone: a story made with its issues, an issue's size, estimate, priority and rank set, a story's issue list and an issue's blockers changed, and a comment written, each against the revision the caller read"
}
```

**Why this size.** L, not M: five writes in the contract the window and a signing client build against, each
through the store API, `core`'s checks and the GitHub store's calls, with the mock board answering them too; not XL,
since title and body edits are an issue of their own ([PL183](README.md#pl183)) and the local directory store takes
the same writes when it is built ([PL179](README.md#pl179)).

**What must be true.** After the cut-over a person, or an agent acting for them, keeps the project's board through the
planner's HTTP API with a signing client, and needs no window and no other way to GitHub.

## Plan

- **Today** the API lists and reads, moves, sets a story's priority and an issue's worker, and adds an issue
  ([PL181](README.md#pl181)) or an idea only as a discussion's hand-in (`POST /ideas` needs its `offer`). Size and
  estimate change only through a task's result; links and comments not at all.
- **`POST /projects/{p}/stories`** (`createStory`): a story with its issues in state Idea, the body of `NewIdea`
  (title, gain, test, priority, one issue per repository) and the revision; no offer. It goes through
  `PlanningStore.createIdea`, so it takes [PL181](README.md#pl181)'s writes, their never-twice guard and their
  `PartlySaved`; answers `201` with the story's and the issues' ids, `409` on a stale revision.
- **`PUT /projects/{p}/issues/{i}/fields`** (`setIssueFields`): any of `size` (XS..XL), `estimateMinutes`
  (1..100000), `priority` (Highest..Lowest), `rank` (1..99999), with the revision; a member absent is left as it is,
  `null` clears it where the field may be empty. Store API: `PlanningStore.setFields(revision, id, Fields)`.
- **`PUT /projects/{p}/stories/{s}/issues`** (`setStoryIssues`): the story's ordered issue list, whole, with the
  revision; an issue taken out loses its parent, one hung in gets the story as parent (GitHub: `addSubIssue` with
  the child's database id, `removeSubIssue`, `reprioritizeSubIssue` for the order). An issue in another story stays
  refused unless named under *Also needs*, as the board allows one parent. Store API: `setStoryIssues`.
- **`PUT /projects/{p}/issues/{i}/blockedBy`** (`setBlockedBy`): the issue's whole list of blockers, with the
  revision; a cycle is refused before anything is written (`core`'s cycle check the board read already uses).
  GitHub: the issue dependency calls. Store API: `setBlockedBy`.
- **`POST /projects/{p}/issues/{i}/comments`** (`comment`): one comment of the person's text, at most 65536
  characters, written with the person's access; the board reads no comment, so it needs no revision. Store API:
  `comment`.
- Each write: refused with `409 StaleRevision` when the revision is not the store's, checked in `core` before any
  call, written under the store's write lock, the board read again after it and announced on the events stream;
  answers carry the new revision. Each store method defaults to `NotSavable("not offered by this store")`; the
  GitHub store and the mock store implement them; the local directory store does when it is built. The window
  needs no change; it may offer these later (its part stays in ST382).
- Red first: per write a controller test (answer, stale revision, malformed body), a `core` test of the refusals
  (cycle, a second parent, an unknown issue), a GitHub store test against the fake (the calls made, nothing on a
  stale revision, a link's child named by its database id), and the mock store answering each.

### As built

- **The checks, in `core`'s `Edits`**: the revision first (`StaleRevision`), then the items (`NoSuchStory`,
  `NoSuchIssue`), then the rule (`EditNotAllowed`, naming the item, the member and the reason). There was no cycle
  check over `blockedBy` to reuse - `IssueMapping` checks circles of parents only - so `Edits.blockedBy` brings its
  own: a blocker named twice, the issue itself, or one leading back to the issue through the board's blockers is
  refused. `IssueMapping` is left as it is, since its id reading is another issue's work. A story's list is refused
  when an issue is named twice, held by another story, a sub-issue of an issue, or when the board does not show
  every issue of the story (`issuesWhole` false), since a list written whole would drop the one not shown. A change
  of fields naming none is refused. A comment is refused blank, past 65536 code points, or when it opens with the
  planner's own marker `<!-- porelo:`, since the GitHub store reads its stage records from such comments.
- **The work record's lock**: `WorkRun.newStory` drops a gone story's run before a story is made, as a hand-in does;
  `WorkRun.storyIssues` refuses a list that takes out an issue, or its sub-issue, a started task works
  (`WorkRunning`). A reorder or an issue hung in leaves a run's bound issues as they are, so the story itself is not
  refused while its work runs. The other edits run under `WorkRun.alone`.
- **A store that does not offer an edit**: `PlanningStore`'s defaults throw `UnsupportedOperationException`, answered
  501 `NotOffered` as an idea a store does not add is, so a window tells a missing feature from a refused write;
  `MoveNotAllowed` stays for a write the store refuses. `LoggedStore`, which wraps every store, forwards each edit.
- **A conflict over a story's issues or a new story** is answered 409 by its own description, `EditConflict`
  (`StaleRevision` or `WorkRunning`), since `IssueConflict` names neither.
- **Each edit is a use case of a service, `BoardEdits`**, which knows no HTTP: it holds the project's lock, the
  revision's and the core's checks, the store's write and the windows told of it. `EditsController` only checks the
  path's ids, reads the body into its record (`EditRequests`, strictly, never repeating what was sent), calls the
  service once and maps its answer and refusals to HTTP. The harms are tested on the service over a fake store,
  without HTTP; the controller's tests check only the mapping. A comment checks no revision and tells the windows
  nothing, since the board shows none. The idea object of a new story is read by the reader a hand-in uses.
- **The mock board takes every edit by hand in memory**: a size on the issue, its estimate, priority and rank kept
  beside the board, which shows none of them; a story's list and an issue's blockers as given; a comment kept
  nowhere. Each answers a new revision and refuses an old one, as a save does.

## Acceptance

- On the test organization's project, the native planner run alone and called through `tool/planner-sign.sh` makes
  a story with two issues, sets an issue's size and estimate, moves an issue into another story's list, adds and
  removes a blocker and writes a comment; each is seen on GitHub, and a call with an old revision changes nothing.
