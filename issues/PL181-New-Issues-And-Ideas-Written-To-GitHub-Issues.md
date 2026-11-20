# PL181 — New Issues and Ideas Written to GitHub Issues

```json
{
  "$schema": "issue-1.json",
  "id": "PL181",
  "title": "New Issues and Ideas Written to GitHub Issues",
  "state": "Implementing",
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

- **Today** `FieldsBoardWriter` does not override `BoardWriter.createIssue` and `createIdea`, whose defaults throw
  "This store does not add issues yet", so the window gets `501` on a GitHub project. The Git store's writes and
  `GitHubStore.createIssue`/`createIdea` (which call the writer under the store's write lock and read the board
  after) stand.
- **`createIssue(current, revision, issue)`**: refuses a stale revision as the other writes do; creates the issue in
  the named repository by REST with the title and a body of the `What must be true` section (`BodySections`), adds it
  to the project (`addProjectItems`) and sets `Status` to the workflow's first state (Idea), `Size` when given,
  `Worker`, and `Rank` after the last of its priority, in one `setFieldValues` batch; answers the tracker reference
  `<repository>#<number>`. A `task` given records which task proposed it, as the Git store records it.
- **`createIdea(current, revision, idea)`**: the story as an issue in the project's own repository labeled `story`
  (checked on the answer: a story without the label stops the write, naming it), with its gain, test and priority
  in body and fields; then each issue as above in its repository, made a sub-issue of the story in the idea's order;
  answers the story's and the issues' references.
- **Never twice**: a call GitHub did not answer (time-out, connection lost after sending) is not retried. The writer
  then looks in that repository for an issue created since the call by the person with that exact title and body:
  one found is taken as made and the write goes on from it; none found makes it once more; more than one stops,
  naming them. A write that stops midway (an idea with some issues made) answers `PartlySaved` naming what stands,
  as the store API defines it.
- **The forge calls**: `ProjectWrites` gains the narrow calls it needs (create an issue with labels, add a
  sub-issue, list a repository's issues created since a time); the carry-over's real run
  ([PL168](README.md#pl168)) uses the same ones.
- Red first: `FieldsBoardWriterTest` against the fake GitHub - an issue made, on the project, with its fields, and
  answered by its reference; an idea's story labeled and its issues its sub-issues in order; a stale revision
  refused with nothing made; an unanswered create found again and not doubled, and made once when not found; a
  story whose label GitHub dropped stopping the write; a partly saved idea naming what stands. A controller test
  that a GitHub project's `POST .../issues` answers the reference instead of `501`.

## Acceptance

- On the test organization's project, the native planner adds an issue and an idea through the window's calls;
  each stands on GitHub with its fields and, for the idea, its sub-issues, and the board shows them under their
  references.
