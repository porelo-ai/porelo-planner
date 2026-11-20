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
| A Porelo sub-issue | A sub-issue of its issue, in the issue's repository, with its own number; never under another sub-issue |
| `blockedBy` | GitHub's issue dependency, "blocked by", across repositories |
| The id | The tracker reference alone, `<repository>#<number>`: the repository the issue lives in, lowercased, and GitHub's number |
| The state | The project's own field `Status`, whose options are the workflow's states in order |
| Priority, size, risk, worker | The single-select fields `Priority`, `Size`, `Risk`, `Worker` |
| The order within a priority | The number field `Rank`, sorted by the planner |
| What is true now | The issue's body, as sections |
| The course in time | One comment per stage |
| The sums of the stages | The number fields `Estimate`, `Minutes`, `VM minutes`, `Tokens`, `Stages` |

The planner adds 10 fields to the project. A GitHub project holds at most 50, 13 of them GitHub's own.

## The body

The body holds what is true now, in the sections the Markdown issues have: the line of what the issue covers first,
then *Why this size*, *What must be true*, *Plan*, *Acceptance*, *Open questions*, *To be checked* and *Findings*.
A story's body also holds *Gain*, *Test* and *Tested*, and an issue's its task, role, severity and kind where it has
them. A stage result overwrites the sections its kind writes, and leaves every other byte as it was. A heading inside
a fenced block is no section. No times and no stage history stand in the body.

A result is written into the body only when the body splits into sections unambiguously: every code block closes;
none runs over a heading-shaped line together with a fence of its own kind at least as long (the sign of a fence left
open by hand that pairs with a later block's); none opened under a section of the skeleton holds a line shaped as the
heading of one; every HTML comment, `pre` and `details` block closes and holds no heading-shaped line; every line
Markdown reads as a heading of the first or second level is written as `## ` and a name; no section of the skeleton
stands twice; and no leading paragraph stands twice. A fence indented by a tab is indented code, no fence. A person's
own headings may repeat, and the leading paragraphs may stand in any order; a missing one is added where the skeleton
puts it. Otherwise the body stays as it is, the result's `Status`, fields and stage comment are still saved,
and the board names the issue by its repository and number with the first thing to repair, such as "the code block
opened in line 12 under Plan never closes". A read takes such a body and shows the issue.

## The stage comments

Each stage is one comment, written with the person's access, so GitHub shows the person as its author. It starts
with the line `<!-- porelo:stage -->`, says the stage and its result in words, and holds a JSON block: a key, the
stage, the result, the actor, the model, the effort, the minutes, the VM minutes and the tokens by kind. The key
names the task and the stage, so a stage written twice counts once.

A stage ends at the comment's `createdAt`, the second GitHub wrote it, and starts that many minutes before; neither
is stored. A person's move is no comment: GitHub's timeline shows each `Status` change with its time, its actor and
the states before and after.

The comments are the course of the work and the number fields its sums. A stage's save adds its minutes, VM minutes,
new tokens and one stage to the fields as they stand right before the write, in the same call as its state, and
writes its comment beside. A field without a value counts as 0, and a value a person set stays the base later stages
add to; the planner never sums the comments again. A stage whose comment, found by its key, already stands is
neither written nor added again. Only an agent's stages of the measured kinds are added, as the Markdown store's
stage records count them; a pause and a person's move are shown, not summed.

## States and ids

- `Status` is the truth. A closed state (Finished, Rejected) also closes the GitHub issue as completed or not
  planned, and reopening sets the state back; an issue whose open or closed state disagrees with its `Status` is
  shown with its `Status` and named as a fault.
- A story whose `Status` is empty follows its issues; a set `Status` is given.
- An item is known by its tracker reference alone, its repository and GitHub's number, and no field holds another
  id. An old id, of an item carried over from Markdown, is matched to nothing: old commits, velocity rows and task
  records stay under it.
- An issue labeled `story` is a story; every other issue of the project is an issue.
- An item a person edited into something the board cannot hold - an item of a repository `project.yml` does not
  name, a `Status` outside the workflow, a parent or a blocker outside the project, a sub-issue in another
  repository than its parent or under another sub-issue - is left out and named as a fault, and the story it belongs
  to says that an issue of it is missing. A fault names the item by its repository and number, never by its title or
  body.

## Writes

A move, a worker, a priority and a stage's result are written with the person's own access, without a commit. Right
before it writes, the planner reads the items it writes again and refuses the save, naming the item, when a person
changed one of them since the board was read: a field value, `Status` included, its parent, its sub-issues, its
blockers, its labels, its open state or its body. GitHub takes no condition on a write, so a change made in the
moment between that read and the write is not caught.

Field values go to GitHub in mutations of at most 50 values. GitHub has no transaction: a save of which GitHub took a
part, or a call GitHub took without answering, is refused as partly saved, naming the repositories a part was saved
in, and the board is read anew. An issue's body and open state are written first, its field values next and a
stage's comment last, so a body GitHub refuses leaves the issue in its state and the result can be handed in again.
A save sets values and never creates a field or replaces its options.

GitHub gives every option a new id when a field's options are set, and clears every item's value of that field. So
the `Status` options follow the workflow's states only through one step of the planner's own, run at the carry-over
or when the person confirms it after the workflow changed, never on a read: it reads every item's `Status` by name,
reads them once more right before it sets the options and stops when a value or an option changed in between, sets the
options, and writes every value back by its new option id, in batches of at most 50. An option the workflow does not
have, held by an item or added by a person, is kept at the end, never dropped, so an item can still be set to it by
hand; each item holding one is named as a fault, without the option's name. Options that already stand as wanted are
not set again; values a batch failed to write back are written again from a fresh read, a value a person set since
stands, and those still missing after the last attempt are named, each with the state it must be given, an option
outside the workflow by its place. A refusal of the person's access or a spent rate limit is not tried again. A stage's
result overwrites the sections of the body its kind writes and no other byte.

A new issue and an idea are added with the person's own access too, and get no `Id`. Before its first call the
planner checks the revision the window showed, the repositories, every field and option it sets and each body, so
what the project cannot hold is refused with nothing made. A new issue is made in its repository with *What must be
true* as its body's paragraph and the task that proposed it below, added to the project, and given the workflow's
first state in `Status`, its `Size` when given, `Worker`, `Priority` (Medium, or the idea's priority) and the
`Rank` after the last issue of that priority. *What must be true* is one paragraph there, without a heading, a lead
or an open code block. An idea's story is made first in the project's own repository, labeled `story`, its gain and
its test in its body, given `Priority` and `Rank`, and no `Status`, so its state follows its issues; then each issue
is made, and they become the story's sub-issues in the idea's order. GitHub drops a label in silence for an account
without push access, so a story made or found without its label stops the write.

A call that makes an issue is never sent twice blindly. When it goes without an answer, the planner reads the
issues the person made in that repository from a minute before the call on, pull requests and the issues the board
or the same write already holds left out: one with the same title and body is taken as made, none makes it once
more, and more than one stops the write, naming them. A call GitHub answered with a failure on its side, or with an
answer that cannot be read, is not sent again. Once a call that makes an issue may have reached GitHub, every stop
is refused as partly saved, naming the repositories, with the references of what GitHub holds in its words, so the
window reads the board anew and never hands the same idea in again. An issue GitHub makes only after the read that
looked for it is not found and is made once more; that moment is the one left.

## Access

The planner reads and writes with the person's own GitHub access, the token Porelo's vault lends it for the forge's
address. The token needs Issues and Contents on the repositories and Projects on the organization. It never stands
in a message, a fault or a log line.
