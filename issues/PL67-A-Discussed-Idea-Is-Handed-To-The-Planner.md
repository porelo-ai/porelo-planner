# PL67 — A Discussed Idea Is Handed To The Planner

```json
{
  "$schema": "issue-1.json",
  "id": "PL67",
  "title": "A Discussed Idea Is Handed To The Planner",
  "state": "Backlog",
  "size": "L",
  "priority": "Medium",
  "rank": 31,
  "blockedBy": [
    "PL58"
  ],
  "covers": "The idea a discussion wrote comes back to the planner when its task ends, with the offer it belongs to, is checked against the idea format and saved once per offer as a story with its issues; a malformed idea makes nothing and says why"
}
```

**Size:** L - a new request in the contract with the window, a checked idea format, a record of taken offers in the
work record with its failure paths, and the window's client and fake.

**What must be true.** When the discussion ends, the person finds the idea on the board as a story in Idea with its
issues, each in its repository, without writing a file by hand; a malformed idea makes nothing and says why, and
handing the same idea in twice makes it once. The store's save is [PL58](README.md#pl58).

## Plan

- **The request.** The task ran on a machine of the person's, so the planner reads no path: the window hands the
  file's content, which Core takes from the ended task, to `POST /projects/{project}/ideas` as `{offer, idea}`.
  The whole body is at most 65536 bytes, read by `WorkRequests.read`; `offer` is the discussion's offer, a canonical
  lowercase UUID as `WorkRun` and `WorkRecord.validate` require of every offer. Any fault of the envelope - too
  large, not JSON, not an object, a member missing or unknown, `offer` no such UUID - answers 422 `IdeaRefused`
  with fixed words, as `WorkRequests.issue` turns a bad body into `IssueNotAllowed`. The answer is 201 `{id}`, the
  story's id, without a revision; `changes.saved` tells the windows, as `createIssue` does. `planner.yaml` describes
  it with its answers, and `Problem.parameters` gains `faults`, a list of strings.
- **The idea's check** is plain Java in `core` (a class beside `NewIssues`); `app` parses the JSON into plain
  records, since `core` has no JSON library. It matches what the store later refuses (`StorySchema`, `IssueSchema`,
  `NewIssues`): a story or issue title is stripped, one line, 1 to 120 code points; `gain` and `test` are stripped,
  one line, 1 to 400 code points; neither holds a control character or line break (`\p{Cntrl}`, U+0080 to U+009F,
  U+0085, U+2028, U+2029 - `NewIssues.BREAKS` misses the C1 range); `mustBeTrue` is not blank and at most 4000;
  priority, size and `worker` are their enums. An issue without `worker` is refused: the person must choose.
  `issues` has 1 item at least and names each repository once (one issue per repository, as `idea-1.json` and
  `IdeaDiscussion`'s brief say), each one of `board.repositories()`, checked before any `NewIssue` is built, whose
  constructor throws on a name such as `a..b` (a 500). `idea-1.json` takes the same limits, so the discussion
  writes what the planner takes. A refusal lists faults by field (`story.title`, `issues[2].worker`) with fixed
  words, at most 100, never repeating what was sent, within the screen's bounds of 200 items of 1000 characters.
- **The order**, all inside one `record.locked` of the work record (its lock outside, `GitStore`'s `ProjectLock`
  inside, as `createIssue` runs under `WorkRun.alone`): validate first, outside the lock, so a malformed idea is
  422 on a taken offer too; then (1) the offer recorded as done answers 201 with its story and writes nothing, even
  for another idea; (2) `WorkRecord.Entry.preflight` checks the record with the offer added; (3) the offer is
  written as pending, as `WorkRun.save` marks a run pending before `store.save`; (4) `PlanningStore.createIdea`
  saves; (5) the offer is written as done with the story's id. The discussion's offer is not recorded when offered:
  whoever holds the planner's token may create a story anyway, so an unknown offer is no harm.
- **The offer record.** The work record keeps the taken offers beside its runs (`WorkRun.Snapshot`,
  `WorkRecord.Document`): each pending, done with its story, or partly saved with the pushed repositories. An
  absent member reads as none, as `WorkRecord.read` defaults a missing `machine`, so a record written before reads;
  `validate` checks each key (canonical UUID), story id (`ST` and a number without leading zero) and repository
  name. At most 1000 done offers are kept; a new one drops the oldest done one.
- **The store's answers.** `NotSavable` answers 422 `IdeaRefused` with the store's words as one fault, not
  `Problems`' `MoveNotAllowed`; `UnsupportedOperationException` answers 501 `NotOffered`. The request carries no
  revision: the planner saves against `store.board()`'s revision read under the lock, and on `StaleRevision`, where
  nothing was pushed, refreshes and tries once more; a second one answers 409 `StaleRevision`. On any fault before a
  push the pending offer is removed, so a retry starts afresh. On `PartlySaved` (502, naming pushed and failed) the
  offer is kept as partly saved. A hand-in of a partly saved or still pending offer (a planner stopped mid-save) is
  refused as `IdeaRefused` naming the repositories already pushed, and writes nothing, so a retry never makes a
  second copy; the issues already pushed stay in Idea without their story.
- **The window's call** lives in the screen package: `Future<String> PlanningSource.handIdea(String project, String
  offer, Map<String, Object?> idea)` answers the story's id, the same on a second hand-in; it writes with
  `revision: false`, since the answer has none, and checks the id's form; a 422 is a `PlanningRefusal` named
  `IdeaRefused` with `parameters.faults`; the fake records each hand-in.
- **Red first**, in `core` over a fake store: the idea check refuses each bound above, a repository named twice,
  and a repository outside the project before any `NewIssue` is built; a taken offer saves nothing; a partly saved
  or pending offer is refused without a save; a `StaleRevision` refreshes once. In `app`: a record without taken
  offers reads, and an envelope fault answers `IdeaRefused`.

## Acceptance

- After a discussion ends, the board shows its story in Idea with an issue in each repository it named; a malformed
  idea leaves every repository as it was and names its faults without repeating what was sent.
- A second hand-in of a saved offer answers the same story and writes nothing; a hand-in of a partly saved offer is
  refused, names the repositories already pushed and writes nothing.

## To be checked

- Whether Jackson's read limits bound the nesting of a 65536-byte body before the idea is parsed into records.
