# PL47 — A Story's Size Beside Its Priority

```json
{
  "$schema": "issue-1.json",
  "id": "PL47",
  "title": "A Story's Size Beside Its Priority",
  "state": "Review",
  "size": "M",
  "priority": "High",
  "rank": 30,
  "blockedBy": [],
  "covers": "A story carries its own size, XS to XL, set when it is estimated; the board's answer carries it beside its priority, and an issue's size, which the answer already carries, shows the same way"
}
```

**Size:** M - one contract change on two sides: the story head's schema, which another repository's stories index
reads too, and the board's answer in the OpenAPI description; the rest is one field passed through four layers.

**Estimate:** 67 min, the measured median of M. Uncertain: whether the other stories index accepts the new field
as it stands; 37 constructor calls of the store's `Story` in tests change with it.

**What must be true.** A person sees each story's size beside its priority on the board, as each issue's; a story's
size is its own, given when it is estimated, not added up from its issues.

## Plan

- **Today.** The board's `Issue` carries `size`, `null` when its head says `-` (`StoreReader`, line 280; OpenAPI
  `Issue.size` is `[string, 'null']`). A story carries none: `story-2.json` and `StorySchema` (store/git `stories`)
  refuse any field they do not name, so a head with `size` is refused today and `StoreReader` drops the story into
  the board's faults. `stories.Story`, the store's `org.fuin.porelo.planner.store.Story`, `GitStore` (the mapping
  near line 1497), `Answers.Story`/`StoryDetail`, `BoardController.story` and OpenAPI `Story` have no size.
- **The head.** An optional `size` in the story head, one of `XS`, `S`, `M`, `L`, `XL`; a story without one omits it.
  It is added to `story-2.json` and to `StorySchema` together, as an optional field (no new schema version: every
  head valid today stays valid); a `story-1.json` head, which names no `$schema`, still refuses it. It is not
  derived from the issues: sizes do not add up, and an estimate of the whole says what the parts do not. The
  planner only reads it; whoever estimates the story writes it into the file, as the other head fields. The other
  stories index takes the same optional field in a change of its own; no story carries a size until it has.
- **Through the layers.** `stories.Story.read` reads it; the store's `Story` gets `@Nullable String size`; `GitStore`
  passes it; `Answers.Story` and `StoryDetail` carry it beside `priority`; OpenAPI `Story` gets
  `size: [string, 'null']`, required as on `Issue`, `null` when the head has none. The mock board
  (`mock/board.json`, read with `FAIL_ON_MISSING_CREATOR_PROPERTIES` by `MockStore`) gets a `size` on every story,
  a few of them set, so the window's tests see both; a mock move keeps it, and the screen's recorded copies of the
  mock's answers (`screen/test/served/mock`) are recorded again with it.
- **Not here.** The badge on the story card and the issue row is the screen package's, built in
  [PL48](README.md#pl48); `PlanStory.from` in `screen/lib/src/models.dart` reads by key and ignores the new member
  until then.
- **Tests, by level.**
  - Unit, store/git: `Story.read` of a head with `"size": "M"` gives size M; one without gives null. Red first:
    today it throws "property 'size' is not defined". Harm: a story with a size vanishing from the board.
  - Unit, store/git: `StorySchemaTest` agrees with `story-2.json` (networknt) on a head with each size and refuses
    `"size": "XXL"` and `"size": 3` in both. Harm: the two checks drifting, so the index takes what the board refuses.
  - Unit, store/git: a `GitStore` read of a story head with size M answers the board's story with M.
  - Integration, app: `BoardReadsTest` answers `size` M and `null`; `ApiMatchesDescriptionTest` holds the answer to
    the description. Harm: the window reading a field the contract does not name.

- **The code reading's findings (Sonnet).** Nothing grave. Taken: a test that a story-1 head with a size is refused
  by both checks, seen red with the story-1 refusal removed. Not taken: a malformed size end to end through the store
  for every bad value, which the schema test already covers for both checks.

## Acceptance

- A story's size from its head is in the board's answer and in the story's own answer; a story without one is
  answered with `null`; a head with a size outside XS to XL is refused by both checks, named in the store's faults.
- The suite and the documents' checks pass; the schema test was seen red with `size` taken out of `StorySchema`.
