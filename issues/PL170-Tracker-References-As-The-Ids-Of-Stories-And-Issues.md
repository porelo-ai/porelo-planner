# PL170 — Tracker References as the Ids of Stories and Issues

```json
{
  "$schema": "issue-1.json",
  "id": "PL170",
  "title": "Tracker References as the Ids of Stories and Issues",
  "state": "Analyzed",
  "size": "M",
  "priority": "High",
  "rank": 87,
  "blockedBy": ["PL164"],
  "covers": "A story or issue kept in a tracker is known on the board, in the window's calls and in a task's start by its tracker reference, a carried-over one showing its old id as its alias, so an item made after the cut-over needs no Porelo id"
}
```

**Why this size.** M, not S: one contract change the window builds against - the id's form in `planner.yaml`, its
paths and the screen - with the store API's `Ids` it rests on; not L, since the store and the board keep their
shape and only what an id may be widens.

**What must be true.** A person sees and moves a story or issue made on GitHub after the cut-over like any other,
and finds a carried-over one under its old id too.

## Plan

- Today an id has one form: `planner.yaml`'s `StoryId` (`^ST[0-9]{1,5}$`) and `IssueId`
  (`^[A-Z]{1,4}[0-9]{1,5}(\.[0-9]{1,3})?$`), checked by `Ids` in `store/api`, which 17 main files of the planner
  call, and by two regexes of the screen (`planner_source.dart`, `story_work_order.dart`); an id stands in the
  paths `/projects/{project}/stories/{story}` and `/projects/{project}/issues/{issue}`.
- An id becomes one of two forms: the Markdown store's, as today, or a tracker reference within the project,
  `<repository>#<number>`, the repository as `project.yml` names it and the number GitHub's. `Ids.story` and
  `Ids.issue` take both; a store answers only its own form. In a path the `#` is percent-encoded (`%23`).
- `Story` and `Issue` gain `alias`, a carried-over item's old id, null for any other, in `store/api` and in the
  answers of `planner.yaml`; the board and the screen show it beside the reference, and every call naming the alias
  finds the item too: a task started before the cut-over reports with the story and issue ids it was started under,
  and the window keeps those in its offers across restarts, so a report under an alias is taken as one under the
  item's reference. The window parses no id and keys its places by the encoded id, so it needs no change.
- A task's start passes the tracker reference with its owner, `owner/repo#123`, as Porelo's `Start` takes it: the
  owner is the repository's, read from its address in `project.yml`.
- The window needs no change, read in its code: it parses no id, keys its kept places by the encoded id, and keeps
  the ids a task was started under in its offers across restarts. What it needs is the alias taken on a report.
- Built in this round after [PL164](README.md#pl164): the contract and the screen first, then the GitHub store's ids,
  with which [PL166](README.md#pl166)'s `createIssue` and `createIdea` answer the new item's reference.
- Red first: a controller test of a board answer and a move by a tracker id, percent-encoded in the path; an `Ids`
  test refusing a reference with a path, white space or a number out of range; a screen test that a tracker id is
  read and its alias shown.

## Acceptance

- The window shows a GitHub board in the test organization with an item made after the cut-over and a carried-over
  one, moves each, and finds the carried one under its alias.
