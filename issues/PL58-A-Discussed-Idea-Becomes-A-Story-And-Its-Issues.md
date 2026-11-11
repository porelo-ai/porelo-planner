# PL58 — A Discussed Idea Becomes A Story And Its Issues

```json
{
  "$schema": "issue-1.json",
  "id": "PL58",
  "title": "A Discussed Idea Becomes A Story And Its Issues",
  "state": "Backlog",
  "size": "L",
  "priority": "Medium",
  "rank": 31,
  "blockedBy": [
    "PL41",
    "PL46",
    "PL57"
  ],
  "covers": "The idea a discussion wrote comes back to the planner when its task ends, is checked against the idea format and becomes, in one save, a story in Idea in the project's repository and an issue in Idea in each repository it names, each with the next free number of that repository's prefix"
}
```

**Size:** L - a new request in the contract with the window, writing new story and issue files with their numbers
and indexes in several repositories in one save, and its failure paths.

**What must be true.** When the discussion ends, the person finds the idea on the board as a story in Idea with its
issues, each in its repository, without writing a file by hand; a malformed idea makes nothing and says why.

## Plan

- The task ran on a machine of the person's, not where the planner runs, so the planner does not read a path: the
  window hands the file's content, which Core takes from the ended task, to `POST /projects/{project}/ideas` with
  the offer it belongs to: `{offer, idea}`, the idea as a JSON object of at most 64 KiB. The planner checks it
  against `idea-1.json` and answers 201 with the new story's id, or 422 naming each fault and writing nothing; an
  offer's idea is taken once: the work record keeps each taken offer's id with the story it made, and a second idea
  for the same offer is answered with that story's id and writes nothing. The discussion's offer itself is not
  recorded; whoever holds the planner's token may create a story anyway, so an unknown offer is no harm.
- The idea is checked beyond the schema too: each repository it names is one of the project's, and a title is one
  line.
- One save, as a move's: each repository the idea names gets an issue file in Idea with the next free number of a
  prefix it owns, its head, and its index row ([PL41](README.md#pl41)'s new idea); then the project's repository gets
  the story file in Idea naming those issues, and the stories index. Others first, the own repository last, all or
  none, as `GitStore.save` pushes today.
- An issue's `worker` is taken from the idea; one without it is refused, as the person must choose.
- The planner answers the new story's id, and the board shows it by its next read.
- The screen package carries the call, so the window never reaches the planner's HTTP itself:
  `Future<String> PlanningSource.handIdea(String project, String offer, Map<String, Object?> idea)` answers the new
  story's id, the same id on a second hand-in of the offer; a 422 is a `PlanningRefusal` named `IdeaRefused` with
  `parameters.faults`, a list of strings; the fake records each hand-in.
- Red first in `store/git`: an idea naming two repositories, saved over local bare repositories, is read back from
  fresh clones as one story in Idea naming two new issues with the next numbers; an idea naming a repository outside
  the project writes nothing.

## Acceptance

- After a discussion ends, the board shows its story in Idea with an issue in each repository it named; a malformed
  idea, or one whose save fails in one repository, leaves every repository as it was and names the fault.
