# PL96 — A New Issue Names The Task It Came From

```json
{
  "$schema": "issue-1.json",
  "id": "PL96",
  "title": "A New Issue Names The Task It Came From",
  "state": "Implementing",
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
- **Tests, red first.**
  - Unit, store/git (`IdeaFileTest`): a named task gives its line in the written file; no task, no line; a task name
    with a line break or a backtick is refused before any write. Harm: a forged line in a committed file.
  - Integration, app: the request with `task` writes it, a malformed `task` is refused naming the field, both against
    the description.

## Acceptance

- An issue handed in with a task names it in its file, one without names none; each new test seen red; the suite and
  the documents' checks pass.
