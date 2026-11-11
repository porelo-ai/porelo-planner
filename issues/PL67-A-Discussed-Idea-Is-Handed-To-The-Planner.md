# PL67 — A Discussed Idea Is Handed To The Planner

```json
{
  "$schema": "issue-1.json",
  "id": "PL67",
  "title": "A Discussed Idea Is Handed To The Planner",
  "state": "Review",
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

- **The request.** The window hands the result file's content with the discussion's offer to
  `POST /projects/{project}/ideas` as `{offer, idea}` (`IdeaRequest`, `Idea`, `IdeaStory`, `IdeaIssue` in
  `planner.yaml`); the answer is 201 `IdeaAdded` `{id}`, the story's id without a revision, and `changes.saved` tells
  the windows. `WorkRequests.handIn` reads the body with `WorkRequests.read` (at most 65536 bytes; Jackson's default
  nesting limit of 500 refuses a deeper body before any record is built). Any fault of the envelope - too large, not
  JSON, not an object, a member missing or unknown, `offer` no canonical lowercase UUID, `idea` no object - answers
  422 `IdeaRefused` with fixed words and one fault; `Problems.unreadable` answers the same name on this path.
  `Problem.parameters` gains `faults`, a list of at most 100 strings of at most 1000 characters.
- **The idea's check** is `NewIdeas` in `core` over plain records (`NewIdeas.Idea`, `Story`, `Issue`), which `app`
  fills from the JSON, a member that is no text read as missing and a member the format does not name reported by
  where it stands. A story or issue title is stripped, one line, 1 to 120 code points; `gain` and `test` are stripped,
  one line, 1 to 400; one line means no character of U+0000 to U+001F, U+007F to U+009F, U+2028, U+2029;
  `mustBeTrue` is not blank and at most 4000; priority, size and `worker` (`Agent` or `Manual`, never left out) are
  their enums; `issues` has one item at least, each repository one of `board.repositories()` and named once, checked
  before any `NewIssue` is built. A refusal lists faults as `<field>: <fixed words>` (`story.title`,
  `issues[0].worker`, counted from 0), at most 100, never repeating what was sent. `idea-1.json` takes the same bounds.
- **The order** is `WorkRun.idea` and `HandedIdeas.save`: the offer's form and the idea are checked first, outside
  the lock, so a malformed idea is 422 on a taken offer too; then, inside one `record.locked`, (1) an offer recorded
  as done answers its story and writes nothing, even for another idea; (2) the idea is checked against the board read
  under the lock, the offer added as pending is preflighted and written; (3) `PlanningStore.createIdea` saves against
  `store.board()`'s revision, and on `StaleRevision` refreshes, checks again and tries once more; (4) the offer is
  written as done with the story's id. On any fault before a push the pending offer is removed, so a retry starts
  afresh; on `PartlySaved` it is kept as partly saved with the pushed repositories. A hand-in of a partly saved or
  still pending offer is refused as `IdeaRefused` with `pushed` and writes nothing.
- **The offer record.** `WorkRun.Snapshot` and `WorkRecord.Document` gain `ideas`, the handed offers by offer, each
  `HandedIdeas.Handed` (status `PENDING`, `DONE` or `PARTLY`, the story's id when done, the pushed repositories, the
  generation it was written at). A record without `ideas` reads as none; `HandedIdeas.validate` refuses an offer
  that is no canonical UUID, a done offer without a story id of `ST` and a number without leading zero, a story id on
  another status, an unknown status, a negative order and a pushed name that is no repository name. At most 1000 done
  offers are kept; a new one drops the oldest done one.
- **The store's answers.** `NotSavable` answers 422 `IdeaRefused` with the store's words as the one fault;
  `UnsupportedOperationException` answers 501 `NotOffered`; a second `StaleRevision` answers 409, `Busy` and an
  unavailable record 503, `PartlySaved` 502. `LoggedStore` passes `createIdea` on to the store it wraps.
- **The mock mode** adds a handed idea to its board in memory (`MockStore.createIdea`): the story numbered after the
  highest, each issue after the highest of its repository's prefix, both in Idea, at a new revision; a reset restores
  the board and forgets the offers.
- **The window's call** in the screen package: `Future<String> PlanningSource.handIdea(String project, String offer,
  Map<String, Object?> idea)` posts `{offer, idea}` with `revision: false` and answers the story's id, refused as
  `BadAnswer` when it is no story id; a 422 is a `PlanningRefusal` named `IdeaRefused` with `parameters['faults']`.
  `MockPlanningSource` records each hand-in in `handedIdeas` and answers one story per offer.
- **Tests**, each seen red under a deliberate break: `NewIdeasTest` and `HandedIdeasTest` in `core` over fakes;
  `IdeasEndpointTest`, `WorkRecordTest`, `MockModeTest` and `ApiMatchesDescriptionTest` in `app`;
  `planner_source_test.dart` and `mock_planning_test.dart` in `screen`.

## Acceptance

- `NewIdeasTest`: an idea at every bound is taken, stripped, with its workers; each field out of bounds, a C1 control
  or line separator in a line, a worker left out or in the contract's own words, a repository named twice or outside
  the project (`a..b` among them) is refused naming that field alone; 300 bad issues give 100 faults, none repeating
  what was sent; `idea-1.json` holds the bounds the check holds.
- `HandedIdeasTest`: a second hand-in answers the same story with one save; a malformed idea is refused on a taken
  offer; a partly saved offer is refused naming `pushed` with no second save; a pending offer is refused without a
  save; one `StaleRevision` refreshes and saves, a second one is answered and leaves the offer free; a store refusal
  leaves the offer free; the offer is pending during the save; an offer that is no canonical UUID is refused; the
  1000 done offers drop the oldest; a recorded offer that fails its check is refused.
- `IdeasEndpointTest`: an idea is saved once, answered 201 with its story and told to a window; every envelope fault,
  a 30000-deep nesting among them, answers 422 `IdeaRefused` with one fault and no echo; a malformed idea lists its
  faults by field; a store refusal answers its words; a store without the save answers 501; `Problems.unreadable`
  answers `IdeaRefused` on the path. `WorkRecordTest`: handed ideas survive a restart, a record without them reads,
  and one that fails its check is refused. `MockModeTest`: the mock saves a handed idea once on its board until a
  reset. `ApiMatchesDescriptionTest`: the contract describes the body, the answer and every refusal.
- The screen's `handIdea` posts exactly `{offer, idea}`, reads the story id and refuses another answer as
  `BadAnswer`, and passes `IdeaRefused` with its faults; the mock answers one story per offer.
