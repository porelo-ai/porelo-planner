# PL57 — Discuss An Idea From The Board

```json
{
  "$schema": "issue-1.json",
  "id": "PL57",
  "title": "Discuss An Idea From The Board",
  "state": "Implementing",
  "size": "M",
  "priority": "Medium",
  "rank": 30,
  "blockedBy": [],
  "covers": "A person chooses Discuss an idea on the board, and the planner answers one task for the window's Start over the project's repositories read as one: a brief that has the agent discuss the idea with the person and write it to a file in the planner's idea format"
}
```

**Size:** M - a new request in the contract with the window, the brief and the idea format in `core`, and the menu
item in the screen package.

**What must be true.** From the board, a person starts a discussion of an idea with an agent of their choice, and the
agent knows what to write when the idea is clear: one file holding the story and an issue for each repository it
concerns.

## Plan

- Today a task is offered only for a story's work (`StoryWorkRequest`, `WorkProposal`, `WorkOrder`): one task per
  repository, with a brief built from the work rules. Nothing offers a task that belongs to no story.
- `POST /projects/{project}/ideas/discussion` takes `{idea}`, the person's first words, 1 to 4000 characters, and
  answers one `PlanningStartTask`: a new `offer`, role `DISCUSSION`, no `repository`, no issues, mode `AGENT`, as
  agent and model the first agent and its model of the first sound work rule (the Start dialog lets the person
  change both), the brief, and `result: "idea.json"`. The window starts it with Porelo's `Start` and
  `allRepositories: true`: every repository of the project read only under `/workspace/<repository>/`, and the file
  handed back from `/run/porelo/result/`. A project without a sound work rule is refused as story work is.
- The offer records nothing and writes nothing to any repository; the window reports no task events for it, as no
  story's run owns it. Taking its idea once is the next step's, by the offer's id.
- `PlanningStartTask` in the contract: role `DISCUSSION` added, `repository` required for every other role and
  absent for it, `result` an optional plain file name.
- The brief (`IdeaDiscussion` in `core`) names the project's repositories and where they are, asks the agent to
  discuss the idea with the person, read what it needs, change nothing, and once the person agrees write
  `/run/porelo/result/idea.json` in the idea format: the story's title, gain, test and priority, and per repository
  it concerns an issue's title, what must be true, its size if known, and `worker` - `Agent` or `Manual`, asked of
  the person, never filled in. The format is a JSON schema the planner holds, `idea-1.json`, and the brief carries it.
- The screen package: `PlanningTaskOrder` reads role `DISCUSSION` with no repository and a `result`; a source's
  `discussIdea(project, idea)`, served by the HTTP source and the mock; the menu item *Discuss an idea* asks for the
  first words and hands the order to the existing `PlanningStartTask` hook with an empty story.
- Tests: red first in `core`, the discussion offer for a project of three repositories names all three in its brief
  and carries the idea schema, and a project without a sound rule is refused; in `app`, the endpoint answers the
  contract's shape and refuses an empty or too long idea; in `screen`, a `DISCUSSION` order with a repository, or
  any other role without one, is refused, and the menu item hands the hook an empty story.

## Acceptance

- Choosing *Discuss an idea* opens the Start dialog, and the started task's brief asks for `idea.json` in the idea
  format; the offer writes nothing to any repository.

## The planner's own clones

Decided: the planner keeps its own clones and does not read the project through the read-all checkout a task gets.
That checkout is assembled when a task starts, read only, on the machine the task runs on; the planner still needs
its own clones to commit and push a move or a new idea with the person's own Git login, reading through the window
and Core would tie the board to a running window and a reachable machine, and on Windows or a Mac the window and the
planner run without Porelo's machine, so the checkout is not there at all. Only the discussion task reads the
project's repositories as one, since an agent needs one tree.
