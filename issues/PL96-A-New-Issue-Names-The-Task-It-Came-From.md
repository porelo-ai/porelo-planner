# PL96 — A New Issue Names The Task It Came From

```json
{
  "$schema": "issue-1.json",
  "id": "PL96",
  "title": "A New Issue Names The Task It Came From",
  "state": "Review",
  "size": "S",
  "priority": "High",
  "rank": 33,
  "blockedBy": [],
  "covers": "An issue the window hands in for an idea a task proposed names that Porelo task, which the new issue's file shows, so a person sees where the idea came from"
}
```

**Size:** S - one optional field through the contract, `store/api`'s `NewIssue` and the Git store's issue writer, with
their tests; no change to the issue head's schema.

**Estimate:** 33 min, the measured median of S.

**What must be true.** When a task proposes an idea while it works, the window hands it to the planner as a new issue in
Idea of the task's repository. The new issue names the task it came from: its file says "Proposed by Porelo task
`<task>`." under what must be true, and a person reading it knows which task to ask.

## Plan

- **Today, read in the code.** `POST /projects/{project}/issues` takes `NewIssueRequest` (repository, title,
  mustBeTrue, size, worker, revision), checks every field and refuses per field (`IssueRefused`, naming it); the core
  makes `NewIssue` (`store/api`) and the Git store writes the file (`IdeaFile`), its number the next free one.
- **The contract.** `NewIssueRequest` gains `task`, optional, a Porelo task name (`TaskLabel`); a malformed one is
  refused naming the field, like the others. Written first in `planner.yaml`; `ApiMatchesDescriptionTest` covers it.
- **Through the store.** `NewIssue` gains `@Nullable String task`, checked as a task name; `IdeaFile` writes the line
  "Proposed by Porelo task `<task>`." as its own paragraph after what must be true when a task is named, and nothing
  when none is. The issue head is unchanged, so the issue schema and `issues check` stay as they are.
- **What a caller could do that the above does not forbid, and what forbids it.**
  - The HTTP layer reads the body by hand, so the contract's pattern is not enforced by itself: `task` joins the
    members a new issue's body may name, and `NewIssues.check` refuses a malformed one naming the field `task`, with
    a fixed reason that never repeats what was sent. An empty string is malformed, not absent; JSON null or a
    non-text is refused as any other member's is; the name is never stripped.
  - One copy of the task name's shape, `Ids.task` in `store/api`, checked with a whole match by `NewIssue`'s
    constructor, so no store and no other caller writes a malformed name; the core's work run uses the same check.
  - The line is its own paragraph: a blank line after what must be true, then the line and one line break.
  - The idea path (`NewIdeas`) names no task and stays without one.
  - The line points to a task; it proves nothing. What must be true is free text from whoever hands the issue in
    and may hold a line of the same words, so nothing reads the line as provenance.
- The code reading's finding: a refused task name was repeated in the store's exception; fixed.
- **The screen passes it on.** The Dart `createIssue` of the planning source, the board, the HTTP source and the
  fakes takes an optional named `task`, sent only when given; the new-issue dialog sends none, since a person proposes
  there.
- **Tests, red first.**
  - Unit, store/api (`IdsTest` or a `NewIssue` test): a task name with a line break, a backtick, a space or nothing
    is refused when the issue is made. Harm: a forged line in a committed file.
  - Store/git (`GitStoreSaveTest`): a named task gives its line as its own paragraph in the committed file; no task,
    no line. Harm: the origin lost or glued to what must be true.
  - Integration, app (`IssuesEndpointTest`): the request with `task` hands it to the store; an empty, malformed,
    null or non-text `task` is refused naming the field, without repeating it. Harm: a malformed name reaches a
    committed file.

## Acceptance

- An issue handed in with a task names it in its file, one without names none; each new test seen red; the suite and
  the documents' checks pass.
