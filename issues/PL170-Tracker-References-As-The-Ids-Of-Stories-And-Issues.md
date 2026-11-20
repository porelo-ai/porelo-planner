# PL170 — Tracker References as the Ids of Stories and Issues

```json
{
  "$schema": "issue-1.json",
  "id": "PL170",
  "title": "Tracker References as the Ids of Stories and Issues",
  "state": "Implementing",
  "size": "M",
  "priority": "High",
  "rank": 87,
  "blockedBy": ["PL164"],
  "covers": "A story or issue kept in a tracker is known on the board, in the window's calls and in a task's start by its tracker reference alone, with no Id field and no alias, so an item made after the cut-over needs no Porelo id; steps 1 and 2 built, the window's hand-over of work to Start next"
}
```

**Why this size.** M, not S: one contract change the window builds against - the id's form in `planner.yaml`, its
paths and the screen - with the store API's `Ids` it rests on, and in step 2 the GitHub store's mapping with the
alias taken out of the contract, the records and the screen again; not L, since the store and the board keep their
shape.

**What must be true.** A person sees and moves a story or issue made on GitHub after the cut-over like any other,
and every item, carried over or new, is known by its tracker reference alone.

## Plan

### Step 2, as decided for the cut-over

- **The id is the tracker reference, nothing else.** A GitHub item's id is `<repository>#<number>`, the repository
  being the GitHub repository the issue lives in, lowercased, as `Ids.name` takes it, and the number GitHub's.
  There is no project field `Id` and no alias: `IssueMapping` builds every id from the item's repository and number,
  `FieldNames.ID` and the `Id` field go (the planner adds 10 fields), and an item whose repository is not one of
  `project.yml`'s, or whose name `Ids.name` refuses, is left out as a fault naming its repository and number.
- **The alias goes again**: `alias` leaves `Story` and `Issue` in `store/api`, `planner.yaml` (`StoryAlias`,
  `IssueAlias`, the members), `Ids.storyAlias`/`Ids.issueAlias`, the mock board, `MockStore`, `WorkRun` and the
  screen (card, issue rows, tile, story page, search, filter). Step 1's tests of the alias go with it. A story is
  told from an issue by the label `story`; a sub-issue of a sub-issue is refused as a fault.
- **`work` filled**: `WorkOrder.Task` carries `work: {issue, phase}` for a planned stage of a GitHub-tracked
  project, the issue `<owner>/<repository>#<number>` with the owner `TrackedProject` verified, the phase the
  stage's; `WorkRecord` reads an older `work.json` without it as absent; `PoreloTaskRecords` takes the owner's
  reference for an issue; a MAIN task over several issues names its first. The window hands `work` to Porelo's
  `Start` (Agent Frontend's side, after the pin moves).
- **A given file's name** for a tracker's issue replaces `#` by `-` (`porelo-planner-12.md`), so no file name
  Porelo mounts holds a `#`.
- Old ids are matched to nothing after the cut-over: old commits, velocity rows and task records stay under them.
  No task is open at the cut-over.
- Red first: `IssueMappingTest` - an item named by its repository and number with no `Id` field on the project, an
  item of a repository outside `project.yml` left out as a fault, a sub-issue of a sub-issue refused; a controller
  test that a board answer carries no `alias`; `WorkOrder`/`WorkRecord` tests of `work` in a start and an older
  record; the screen's tests without the alias.
- Built so: `IssueMapping` names each item by its repository and number, the repository lowercased as GitHub may
  name it in capitals; an item whose name `Ids.name` refuses, of a repository outside `project.yml`, standing twice,
  or a sub-issue under a sub-issue - whichever order the items come in - is left out as a fault naming repository and
  number. A story under another issue keeps its place on the board, shown without the parent, with a fault saying so.
- `work` built so: the board carries the owner its store verified, and `StageOrder.staged`, which every planned
  task passes, names the first issue the task owns with that owner, and the phase whose states hold the stage's
  working state, the state's own name where no phase names it; none for a discussion, a task owning no issue or a
  project no tracker keeps. `PoreloTaskRecords` takes `<owner>/<repository>#<number>` for the task's and each call's
  issue and counts it for `<repository>#<number>`, the issue as the board names it, and takes any phase of one line
  and at most 64 characters, as the window hands it to `Start`. A stage's instructions name an issue by its id as
  the order names it, not by a number the planner links.

### Step 1, built

What step 1 built of the alias is taken out by step 2; the rest stands.

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

### Plan reading

Where this reading and "Step 2, as decided for the cut-over" differ, step 2 holds: there is no alias and no `Id`
field, so every point below about finding an item by its alias is void.

Read by another model before the build. Step 1 builds the contract, `Ids`, the records and the screen; step 2, the
GitHub store's ids, builds what only a store that keeps references and aliases can answer. How each point is
answered:

- **The owner in `work.issue`** comes from the owner the store verified for a tracked project (`TrackedProject`),
  never from `project.yml`'s `central` entry. Step 1 only describes `work`; step 2 fills it.
- **`work: {issue, phase}`** stands in `planner.yaml`'s `PlanningStartTask` as `TrackedWork`, only on a planned
  stage's task of a project a tracker keeps, never on a discussion's, absent and never null otherwise: `issue` is
  `<owner>/<repository>#<number>`, one line without white space, at most 200 characters; `phase` is the name of the
  stage's phase, one line, at most 64 characters. The screen reads it as `PlanningWork` on `PlanningTaskOrder` and
  refuses an order whose `work` is not that shape or stands on a discussion. Step 1 always answers it absent.
  Step 2 adds it to `WorkOrder.Task`, with `WorkRecord` writing it null into an older `work.json` so that file
  stays readable; says which issue a MAIN task over several issues names; widens `PoreloTaskRecords`' issue check to
  the owner's reference; has `Velocity` sum a carried issue's records under its reference and its alias; and has the
  window hand `work` to `Start`.
- **A refused id is never repeated**: `Ids.story`, `Ids.issue` and the alias checks name the shape and the length
  only, as `Ids.name` and `Ids.task` do.
- **Sub-issues**: `Issue` takes a tracker sub-issue whose parent is a reference in the same repository, and refuses
  a reference into another repository than the issue's own, an issue and parent of different forms, and an alias on
  an item of the Git store. Telling a story from an issue by the `story` label, refusing a sub-issue of a sub-issue,
  checking an item's repository and number before its reference is built, reading an empty `Id` field as no alias,
  and never writing a reference into the `Id` field belong to the GitHub store's mapping, step 2.
- **The id's shape**: a reference is `<repository>#<number>`, the repository as `Ids.name` takes it, without `..`,
  the number `[1-9][0-9]{0,8}`; `planner.yaml` and the screen refuse `..` too, by a lookahead. An alias takes only
  the Git store's form (`Ids.storyAlias`, `Ids.issueAlias`; `StoryAlias`, `IssueAlias`). `HandedIdeas` takes a
  reference as a handed idea's story. The Git store needs no change: `GitRemote.grep` passes ids as fixed strings,
  and a reference finds no item there, so it answers it as unknown. A story's reference into another repository than
  the own one, an alias unique over the board, and a given file's name for a tracker's issue in `GivenFile.name`
  (a `#` in a file name Porelo mounts needs the window's agreement) are step 2.
- **Calls by alias**: `Board.story` and `Board.issue` finding an item by its alias, and every entry point resolving
  an alias to the reference - moves, workers, priorities, proposals, orders, and the runs, offers, task reports and
  stage results a pre-cut-over task recorded under its old ids - are step 2, since only the GitHub store gives
  aliases. Step 1 answers an issue's sub-issues by the issue's own id rather than the id called. The screen reads
  `alias`, shows it beside the id on the board and the story's page, keeps it across a move, and finds an item by
  it in the board's search and the issue filter; taking an answer whose alias matches the id asked is step 2.
- **Tests**: step 1 sees red first the shape (`a..b#1`, `repo#0`, a leading zero, a second `#`, white space), an
  alias that is a reference, no repeated value, a board read and a move by a percent-encoded reference through the
  controllers and through the real server, the description's id patterns against `Ids`, and the screen's reading
  and encoding. A GitHub sub-issue, a pre-cut-over run reported after the cut-over, a batch naming one item by
  reference and alias, and `work` in a start and in Porelo's records are tested in step 2.
- **The code reading of step 1**: the GitHub store's `Id` field takes only the Git store's form, so a reference
  typed into it is no valid id, as before the cut-over; `work.issue` refuses `..` in the owner too, and the screen
  takes `work` only for one of the task's own issues; `TrackedWork` takes no other member and no blank phase. A
  copy of an issue or a story keeps its alias, and a story's run is looked up under the story's own id. Two points
  are kept without a test, since nothing in step 1 can show them: the alias kept on the board `WorkRun` stages for
  its checks, which nothing there reads, and the run looked up under the story's own id rather than the id called,
  which differ only once `Board.story` finds an item by its alias in step 2. The mock store's copies keep the alias
  as well, though its board holds none.

## Acceptance

- The board of a GitHub project in the test organization, with no `Id` field, shows an item made after the
  cut-over and a carried-over one, each by its tracker reference, and moves each, measured on the native planner.
