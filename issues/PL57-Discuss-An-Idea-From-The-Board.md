# PL57 — Discuss An Idea From The Board

```json
{
  "$schema": "issue-1.json",
  "id": "PL57",
  "title": "Discuss An Idea From The Board",
  "state": "Idea",
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
- `POST /projects/{project}/ideas/discussion` answers one offer as a story's start does - agent and model as the
  person chose them in the Start dialog, the brief, role `DISCUSSION` and no story, and as its repository the
  project's repositories read as one, a read-only checkout assembled when the task starts - for the window to start
  with Porelo's `Start`. The request takes the person's first words of the idea.
- The brief asks the agent to discuss the idea with the person and, once the person agrees, to write
  `idea.json` in the idea format: the story's title, gain, test and priority, and per repository it concerns an
  issue's title, what must be true, its size if known, and `worker` - `Agent` or `Manual`, asked of the person, never
  filled in. The format is a JSON schema the planner holds, `idea-1.json`, and the brief carries it.
- The screen package gets the menu item *Discuss an idea*, opening the Start dialog for this offer.
- Red first in `core`: the discussion offer for a project of three repositories names the repositories read as one,
  read only, and its brief holds the idea schema.

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
