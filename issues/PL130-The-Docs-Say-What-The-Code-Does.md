# PL130 — The Docs Say What The Code Does

```json
{
  "$schema": "issue-1.json",
  "id": "PL130",
  "title": "The Docs Say What The Code Does",
  "state": "Backlog",
  "size": "S",
  "priority": "Highest",
  "rank": 4,
  "blockedBy": [],
  "covers": "Every sentence of the repository's user-facing documents - the READMEs, doc/ and the changelog's unreleased entries - is borne out by the code on main: no command, flag, path, default, state, message or behavior that is gone, renamed or never was"
}
```

**Size:** S - about 450 lines of documents in one repository, read against its code; no behavior changes.

**What must be true.** A person who follows the README, `doc/index.md` or a module's README finds the commands,
paths, defaults and behavior the planner and the screen actually have.

## Plan

- A reader of another model reads `README.md`, `CONTRIBUTING.md`, `SECURITY.md`, every module `README.md`,
  `doc/index.md`, `doc/decisions.md` (what holds now, not its history) and `changelog/unreleased/` page by page
  against the code on main, and lists each sentence the code does not bear out, with the document's file and line, the
  claim, and the code's file and line that contradicts it. It runs no test suite and no VM.
- Each finding is checked in the code before anything changes. Where the code is right, the document is corrected;
  where the document is right and the code is wrong, the defect is filed as an issue of its own and the document stays.
- No red test: the change is to documents only, and the tests tagged `documents` hold their form.

## Acceptance

- The tests tagged `documents`, `check-commits`, `check-citations` and the trace check pass on the commit; the hand-over
  names the count of claims checked, confirmed, fixed and filed as defects.
