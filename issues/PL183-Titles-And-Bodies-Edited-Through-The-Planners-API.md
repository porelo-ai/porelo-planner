# PL183 — Titles and Bodies Edited Through the Planner's API

```json
{
  "$schema": "issue-1.json",
  "id": "PL183",
  "title": "Titles and Bodies Edited Through the Planner's API",
  "state": "Implementing",
  "size": "M",
  "priority": "Highest",
  "rank": 3,
  "blockedBy": ["PL182"],
  "covers": "A story's or an issue's title and named body sections are changed through the planner's API against the revision the caller read, without losing a person's edit made on the tracker meanwhile; built right after the cut-over"
}
```

**Why this size.** M, not L: one contract change (two operations of one shape), a store API method, `core`'s checks,
the GitHub writer's update with the title and two story parts more in `BodySections`, the mock board, the doc page and
an acceptance step, all on [PL182](README.md#pl182)'s write path and revision check; every named section being
editable adds members of the same shape, not a mechanism; it crosses no repository.

**What must be true.** A person, or an agent acting for them, corrects a story's or an issue's title and the text of
every named section through the planner alone - the coordinator writes the agents' analyses into the issues this
way - and every byte of the body outside the sections named stays as it was. An edit made on GitHub since the caller read the
board is never overwritten unseen: the write is refused and the caller reads again.

## Plan

- **Today** a title and a body are written only by a new issue or idea and by a stage result, which overwrites its own
  parts (`BodySections.withResult`). `ProjectWrites.updateIssue` sends the body and the open state, the title always
  null. Right before a write, `FieldsBoardWriter.Plan.unchanged` reads every item it writes again and compares its
  `Seen` (fields, links, labels, open state, body) with the board's; the title is not in `Seen`, so a title a person
  changed since the last read would be overwritten unseen.
- **Contract** (`planner.yaml`, tag `board`): `PUT /projects/{p}/issues/{i}/text` (`setIssueText`) with
  `IssueTextRequest` `{revision, title?, covers?, whyThisSize?, whatMustBeTrue?, plan?, acceptance?, openQuestions?,
  toBeChecked?, findings?}` and `PUT /projects/{p}/stories/{s}/text` (`setStoryText`) with `StoryTextRequest`
  `{revision, title?, gain?, test?}`; `additionalProperties: false`, a member left out stays, `null` removes a section
  (every one but *covers* and *What must be true*, and never the title).
  A title is one line without control characters, at most 120 characters, as `NewIssueRequest`'s; a section at most
  65536 characters. Answers `200 EditSaved` (equal text saves nothing and answers the current revision), `409
  EditConflict` (StaleRevision, naming the item a person changed), `422 EditRefused` (EditNotAllowed: no member, a
  title or section refused, the body would exceed 65536, or the body's sections cannot be told apart, with
  `Ambiguous.repair()`'s fixed words), `501 NotOffered`, `502`/`503`/`504` as the other edits. The read stands:
  `IssueDetail.text`, `whatMustBeTrue` and `StoryDetail.description`.
- **Every named section is editable**, those a stage writes (*Why this size*, *Plan*, *To be checked*, *Findings*)
  included, since after the cut-over the coordinator writes the agents' analyses into the issues through this edit.
  **Not written by it**: *Also needs* (a story's issue list, [PL182](README.md#pl182)'s `setStoryIssues`), a person's
  own headings, the fields and the stage comments; the request has no member for them. A stage result written after
  the board read moves the revision, so an edit of the same section is refused, not lost.
- **Store API**: `PlanningStore.setText(Revision, String id, TextEdit)` answering the `Board`, default
  `UnsupportedOperationException(NOT_OFFERED)`, answered 501 as the other edits are; `TextEdit` in `store/api`
  holds the title and a map of `TextEdit.Part` (`COVERS`, `WHY_THIS_SIZE`, `WHAT_MUST_BE_TRUE`, `PLAN`,
  `ACCEPTANCE`, `OPEN_QUESTIONS`, `TO_BE_CHECKED`, `FINDINGS`, `GAIN`, `TEST`) to text or removal. `LoggedStore` forwards it. The Git store does not offer it.
- **`core`**: `Edits.text(board, revision, id, edit)` - `fresh` first, then the item, then the rule: a story takes
  only title, gain and test, an issue only its eight parts; no member, a blank or multi-line title, or a removal of a
  part that may not be removed is `EditNotAllowed`. `BoardEdits.setText` runs it under the project's `WorkRun.alone`,
  writes, tells the windows; `EditsController` only maps. A task at work on the issue does not refuse the edit: its
  result is checked against the revision it read, as every write is.
- **GitHub** (`FieldsBoardWriter.setText`): `stale`, the item, `BodySections.read(item.body())`, each part through
  `with`/`without`, the result checked with `ambiguity()` (today `with` checks headings, leads and fences but not an
  HTML comment or `details` block left open, which would make every later stage result skip the body), then
  `Plan.title`/`Plan.body` and `Plan.write`: under the store's lock it reads the item again and compares `Seen`, now
  with the title, and sends title and body in one `updateIssue` (title passed to `IssuesUpdateRequest`). The new body
  is built from exactly the body that read confirms, so a section the person did not name keeps every byte. GitHub
  takes no condition on an issue update: **an edit a person makes between that read and the PATCH, one round trip, is
  overwritten**; it stays in GitHub's own edit history of the body and the title's rename event. `BodySections` gains
  `GAIN` (`**What a user gains.**`) and `TEST` (`**The test.**`) as leading parts, the shape `NewItems.story` writes,
  and `lead()` knows them.
- **Mock board**: `MockStore.setText` refuses an old revision, writes the title and the parts through `BodySections`
  into the text it keeps in memory and answers the next revision, as its other edits.
- **Local directory store** ([PL179](README.md#pl179)) takes `setText` when it is built: its bodies are
  `BodySections`' and its revision is the files' digest.
- **Doc**: `doc/github-issues.md` *Writes* says which parts an edit writes, the check and the window left.
- Red first, each with the harm it guards:
  - *a person's edit lost*: `FieldsBoardWriterTest` - the fake's title, then its body, changed after the board read:
    `StaleRevision` naming the item, no `updateIssue` (the title case is red today, `Seen` lacks it);
  - *a section not named overwritten*: `BodySectionsTest` and the writer test - an edit of *What must be true* leaves
    *Plan*, *Findings* and a person's own section byte for byte, and an edit of *Plan* leaves *Findings* and *What
    must be true* so; `EditsEndpointTest`: a member `alsoNeeds` is 422, a `null` for `whatMustBeTrue` is 422;
  - *a stale revision writing*: `BoardEditsTest` and the writer test - an old revision is 409, no call;
  - *a body over GitHub's limit*: a section that grows the body past 65536 is 422, nothing written;
  - *a section heading injected through the text*: a `## Plan` line, a `**Why this size.**` lead in a leading part,
    an open fence, an HTML comment or `<details>` left open, a title with a line break - each 422, nothing written;
  - `core`'s `EditsTest` of the rules, `MockStoreTest` answering and refusing, `ApiMatchesDescriptionTest`.

## Acceptance

- On the test organization's project, the native planner run alone and called through `tool/planner-sign.sh` sets an
  issue's title, *What must be true* and *Plan*, and a story's gain: each is seen on GitHub, and the issue's
  *Findings* and the story's other text are byte for byte as before. A person then edits the issue's body on GitHub; the planner's edit
  with the revision read before is refused 409 and the person's text stands. An edit whose text holds a `## Plan`
  line is refused 422 and GitHub's body is unchanged.
- The edit is checked against the board's revision, and the item is read again under the store's lock before the
  write; the one round trip between that read and GitHub's update, which GitHub cannot make conditional, is accepted
  and said in `doc/github-issues.md`.

## To be checked

- How carried-over stories and `NewItems.story` lay out gain and test against `BodySections`' leading parts (a
  story's first paragraph is read as *covers* today), and whether a *Tested* part, which `doc/github-issues.md` names,
  stands in any body.
- That `IssuesUpdateRequest` with only `title` and `body` leaves labels, assignees and state untouched on GitHub,
  read against the fake and once on the test project.
