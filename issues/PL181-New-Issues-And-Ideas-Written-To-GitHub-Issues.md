# PL181 — New Issues and Ideas Written to GitHub Issues

```json
{
  "$schema": "issue-1.json",
  "id": "PL181",
  "title": "New Issues and Ideas Written to GitHub Issues",
  "state": "Review",
  "size": "M",
  "priority": "Highest",
  "rank": 1,
  "blockedBy": ["PL170"],
  "covers": "The GitHub store adds a new issue and an idea - a story with its issues - with the person's own GitHub access, each known by its tracker reference, never twice for one call"
}
```

**Why this size.** M: two writes of the GitHub store (`FieldsBoardWriter.createIssue`, `createIdea`) over the forge
client's REST and GraphQL calls, the guard against a doubled item on an unanswered call, and their tests; no
contract change, since the window's `POST .../issues` and `.../ideas` and their answers stand.

**What must be true.** On a project kept in GitHub Issues the person adds an issue or an idea from the window as on
any project, and finds it on the board and on GitHub under its tracker reference.

## Plan

- **Before** `FieldsBoardWriter` overrode neither `BoardWriter.createIssue` nor `createIdea`, whose defaults throw
  "This store does not add issues yet", so the window got `501` on a GitHub project. The Git store's writes and
  `GitHubStore.createIssue`/`createIdea` (which call the writer under the store's write lock and read the board
  after) stand.
- **Checked before the first call**: the revision the window showed (`StaleRevision`, for an issue and an idea
  alike), the project's id, each repository being one of the project's (and named once in an idea), every field and
  option a value is set to, each title and line, and each body. `What must be true` of one paragraph the lead
  holds is written as the lead `**What must be true.** <text>`; one of several paragraphs, or one the lead cannot
  hold, as a section `## What must be true` of its own (`BodySections.withWhatMustBeTrue`). A text even a section
  cannot hold, with a heading of the first or second level or a code block left open, is `NotSavable` in fixed words
  that never repeat the person's text, never `Unreachable`. `BodySections.part(WHAT_MUST_BE_TRUE)` and the issue
  detail's `whatMustBeTrue` read either form; a body holding both is ambiguous, named as a fault of the item as other
  ambiguities are, and no stage result is written into it.
- **`createIssue(current, revision, issue)`** (`NewItems`, called by `FieldsBoardWriter`): makes the issue in the
  named repository by REST with its title and a body of `**What must be true.** <text>`, followed by ``Proposed by
  Porelo task `<task>`.`` as its own paragraph when a task proposed it, as the Git store writes it; in the section
  form the task's paragraph stands above the section, so the section holds the person's text alone; adds it to the
  project (`addProjectItems`) and sets `Status` to the workflow's first state, `Size` when given, `Worker`,
  `Priority` Medium and `Rank` after the last issue of that priority, in `setFieldValues` batches of at most 50;
  answers `<repository>#<number>`. No `Id` is written. A rank counts only finite ranks of 1 to 99999 and is capped
  at 99999.
- **`createIdea(current, revision, idea)`**: the story first, in the project's own repository, labeled `story`,
  with `**What a user gains.** <gain>` and `**The test.** <test>` as its body, `Priority` the idea's and `Rank`
  after the last story of that priority, and no `Status`, so its state follows its issues; then each issue as above
  with the idea's priority; all added to the project, each issue made a sub-issue of the story (by its REST id) in
  the idea's order; answers the story's and the issues' references. The `story` label is checked in GitHub's answer
  and in an issue found again after an unanswered call: a story without it stops the write, naming it.
- **Never twice**: only a call sent without an answer (`Delivery.SENT_UNANSWERED`) is looked after: the issues
  the person (the token's login, case aside) made in that repository from 60 seconds before the call on, open or
  closed, pull requests and the numbers the board or this write already holds left out, are read in at most 10
  pages; one of exactly that title and body is taken as made and the write goes on from it; none makes it once more;
  more than one stops, naming them. A call refused by GitHub (4xx) or never sent made nothing; one GitHub answered
  with a failure on its side (5xx) or with an answer that cannot be read is never sent again and stops the write.
- **What a stop answers**: once any call that makes an issue made one or may have, every later stop is
  `PartlySaved`: its failed repository and `pushed` hold repository names only, in write order, as `work.json` reads
  them, and the references GitHub holds stand in its reason. Before that, a refusal is the call's own (`NotSavable`,
  `NoForgeAccess`, `Unreachable`), its words scrubbed with the one token taken for the write.
- **The forge calls**: `ProjectWrites` gains `addProjectItems(token, projectId, issueIds)`, `createIssue(token,
  owner, repository, title, body, labels)` answering the node id, the REST id, the number and the labels,
  `addSubIssue(token, owner, repository, number, subIssueId)` and `issuesCreatedSince(token, owner, repository,
  since, page)`, which reads the token's login and the repository's open issues since that moment, so a duplicate the person closed is never taken for one made; the
  carry-over's real run ([PL168](README.md#pl168)) uses the same ones. `StoreConfiguration` registers the new REST
  model classes for the native image.
- Red first, each seen red with its guard broken: `FieldsBoardWriterTest` against the fake GitHub - an issue made,
  on the project, with its fields and no `Id`, answered by its reference; an idea's story labeled and its issues its
  sub-issues in order; a stale revision refused with nothing made, for an issue and an idea; an unanswered create
  found again and not doubled; one not found made once; an issue alike that stood on the board not taken; two found
  stopping the write naming both; a story whose label GitHub dropped, answered or found again, stopping the write; a
  failure on GitHub's side not sent again and partly saved; a refusal after an unanswered call partly saved; a
  partly saved idea naming what stands without the token or the person's text; a *What must be true* the body cannot
  hold refused before any call; a *What must be true* of several paragraphs made whole as its own section.
  `BodySectionsTest`: the section form read whole, written for several paragraphs and the lead kept for one, and a
  body holding both forms named as unclear. `TrackerIdsHttpTest`: the section form answered whole as
  `whatMustBeTrue`. `GitHubAddsHttpTest`: a GitHub project's `POST .../issues` and `POST .../ideas`
  answer the reference instead of `501`.
- Reading the board back after a write finds the new items only once the board reads every item by its tracker
  reference ([PL170](README.md#pl170)); until then a new item stands on GitHub and the project but the board leaves
  it out as having no `Id`, so the tests check GitHub's calls and state, not the board read after.

## Acceptance

- On the test organization's project, the native planner adds an issue and an idea through the window's calls;
  each stands on GitHub with its fields and, for the idea, its sub-issues, and the board shows them under their
  references.
