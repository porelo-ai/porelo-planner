# Stories and issues in GitHub Issues

A project whose `project.yml` names GitHub Issues as its tracker keeps its stories and issues in a GitHub project
instead of Markdown files. This page says where each part of a story and an issue stands there. The workflow and
the work rules stay files in the project's own repository.

## What stands where

| Porelo | GitHub |
|---|---|
| A story | An issue in the project's own repository, labeled `story` |
| A story's issues | Its sub-issues, in their own repositories, in the story's order |
| An issue in a second story | Listed under *Also needs* in that story's body; GitHub allows one parent |
| A Porelo sub-issue, numbered after its issue's id and a dot | A sub-issue of its issue, with its own number |
| `blockedBy` | GitHub's issue dependency, "blocked by", across repositories |
| The id | The tracker reference, `<repository>#<number>` |
| An old id, of an item carried over from Markdown | The project's text field `Id`, its alias |
| The state | The project's own field `Status`, whose options are the workflow's states in order |
| Priority, size, risk, worker | The single-select fields `Priority`, `Size`, `Risk`, `Worker` |
| The order within a priority | The number field `Rank`, sorted by the planner |
| What is true now | The issue's body, as sections |
| The course in time | One comment per stage |
| The sums of the stages | The number fields `Estimate`, `Minutes`, `VM minutes`, `Tokens`, `Stages` |

The planner adds 11 fields to the project. A GitHub project holds at most 50, 13 of them GitHub's own.

## The body

The body holds what is true now, in the sections the Markdown issues have: the line of what the issue covers first,
then *What must be true*, *Why this size*, *Plan*, *Acceptance*, *Open questions*, *To be checked* and *Findings*.
A story's body also holds *Gain*, *Test* and *Tested*, and an issue's its task, role, severity and kind where it has
them. A stage result overwrites the sections its kind writes, and leaves every other byte as it was. A heading inside
a fenced block is no section. No times and no stage history stand in the body.

## The stage comments

Each stage is one comment, written with the person's access, so GitHub shows the person as its author. It starts
with the line `<!-- porelo:stage -->`, says the stage and its result in words, and holds a JSON block: a key, the
stage, the result, the actor, the model, the effort, the minutes, the VM minutes and the tokens by kind. The key
names the task and the stage, so a stage written twice counts once.

A stage ends at the comment's `createdAt`, the second GitHub wrote it, and starts that many minutes before; neither
is stored. A person's move is no comment: GitHub's timeline shows each `Status` change with its time, its actor and
the states before and after.

The comments are the truth and the number fields their sums; the planner writes both in one save. The sums count
the closed agent stages of the measured kinds, as the Markdown store's stage records do; a pause and a person's move
are shown, not summed. A field that disagrees with its comments' sum, after a hand edit on GitHub, is shown as the
sum and named as a fault, and the next save of that issue writes it again. A comment with the marker that cannot be
read is named as a fault and left out of the sums.

## States and ids

- `Status` is the truth. A closed state (Finished, Rejected) also closes the GitHub issue as completed or not
  planned, and reopening sets the state back; an issue whose open or closed state disagrees with its `Status` is
  shown with its `Status` and named as a fault.
- A story whose `Status` is empty follows its issues; a set `Status` is given.
- An item is known by its tracker reference, its repository and GitHub's number. A new story or issue gets no
  other id. One carried over from Markdown keeps its old id in `Id` as an alias, never given again, so commits and
  decisions that name it still find it.
- An item a person edited into something the board cannot hold - an alias twice, a `Status` outside the
  workflow, a parent or a blocker outside the project, a sub-issue under another issue than its id names - is left
  out and named as a fault, and the story it belongs to says that an issue of it is missing. A fault names the item
  by its repository and number, never by its title or body.

## Access

The planner reads and writes with the person's own GitHub access, the token Porelo's vault lends it for the forge's
address. The token needs Issues and Contents on the repositories and Projects on the organization. It never stands
in a message, a fault or a log line.
