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
| `blockedBy` | GitHub's issue dependency, "blocked by", on an issue of the project |
| `waitsOn` | GitHub's "blocked by" on an issue the project does not hold, such as one of another owner's repository |
| The id | The tracker reference alone, `<repository>#<number>`: the repository the issue lives in, lowercased, and GitHub's number |
| The state | The project's own field `Status`, whose options are the workflow's states in order |
| Priority, size, risk, worker | The single-select fields `Priority`, `Size`, `Risk`, `Worker` |
| The order within a priority | The number field `Rank`, sorted by the planner |
| What is true now | The issue's body, as sections |
| The course in time | One comment per stage |
| The sums of the stages | The number fields `Estimate`, `Minutes`, `VM minutes`, `Tokens`, `Stages` |

The planner adds 10 fields to the project. A GitHub project holds at most 50, 13 of them GitHub's own.

## The body

The body holds what is true now, in the sections the Markdown issues have: the line of what the issue covers first, then
*Why this size*, *What must be true*, *Plan*, *Acceptance*, *Open questions*, *To be checked* and *Findings*. *What must
be true* of one paragraph stands as the paragraph its lead `**What must be true.**` opens; one of several paragraphs, or
one the lead cannot hold, stands as a section `## What must be true` of its own, and the planner reads either form. A
story's body opens with *What a user gains* and *The test*, each a paragraph after its lead, and an issue's body holds
its task, role, severity and kind where it has them. A stage result overwrites the sections its kind writes, and leaves
every other byte as it was. A heading inside a fenced block is no section. No times and no stage history stand in the
body.

A result is written into the body only when the body splits into sections unambiguously: every code block closes; none
runs over a heading-shaped line together with a fence of its own kind at least as long (the sign of a fence left open by
hand that pairs with a later block's); none opened under a section of the skeleton holds a line shaped as the heading of
one; none opened above the first section holds a heading-shaped line or a lead; every HTML comment, `pre` and `details`
block closes and holds no heading-shaped line; every line Markdown reads as a heading of the first or second level is
written as `## ` and a name; no section of the skeleton stands twice; no leading paragraph stands twice; and *What must
be true* does not stand both as the lead's paragraph and as a section. A fence indented by a tab is indented code, no
fence. A person's own headings may repeat, and the leading paragraphs may stand in any order; a missing one is added
where the skeleton puts it. Otherwise the body stays as it is, the result's `Status`, fields and stage comment are still
saved, and the board names the issue by its repository and number with the first thing to repair, such as "the code
block opened in line 12 under Plan never closes". A read takes such a body and shows the issue.

## The stage comments

Each stage is one comment, written with the person's access, so GitHub shows the person as its author. It starts
with the line `<!-- porelo:stage -->`, says the stage and its result in words, and holds a JSON block: a key, the
stage, the result, the actor, the model, the effort, the minutes, the VM minutes and the tokens by kind, and for a
summed stage its `base`, the issue's Minutes, VM minutes, Tokens and Stages before the stage was added to them. The
key names the task and the stage, so a stage written twice counts once.

A stage ends at the comment's `createdAt`, the second GitHub wrote it, and starts that many minutes before; neither
is stored. A person's move is no comment: GitHub's timeline shows each `Status` change with its time, its actor and
the states before and after.

A move into a closed state may carry the person's one sentence why: what was built, or why not. It is written as a
comment after the close, on the story whose shown state the move makes closed and on each issue the move closes; when
the story stands in that state already, the same move sent again, each issue of it standing there too keeps the
sentence, so a comment that failed is written then. An issue's own move in the same batch keeps its own sentence or
none. The comment's first line is `<!-- porelo:closing <state> <hash> -->`, where the hash is the first twelve
hexadecimal characters of the sentence's SHA-256, then `<state>: ` and the sentence as one code span, so nothing in
it mentions a person, references an issue or shows a link, an image or HTML. The marker is read before the write, so
the same move sent again after a comment that failed writes only what is missing and never one twice, while a new
sentence after a reopen is written again. The Git store keeps no reason, and a preview or save that carries one
there is refused before anything is written.

The comments are the course of the work and the number fields its sums. A stage's save adds its minutes, VM minutes,
new tokens and one stage to the fields as the board read them, in the same call as its state, and refuses the save,
naming the issue, when GitHub's value changed since. Its comment is written before the fields and names, as `base`,
the four fields as they stood before the stage was added to them. A field without a value counts as 0, and a value a
person set stays the base later stages add to; the planner never sums the comments again. A stage whose comment,
found by its key, already stands is not written again, and its figures are added only to a field that still stands
at the comment's base, so a result handed in again after a save cut off between the comment and the fields counts
the stage once; with a comment that names no base, none is added again. A summed field that no longer stands at the
base when the result is handed in again counts the stage as added there, so a person's edit of it between the two
tries, or another stage of the issue saved in between, is kept and the stage's figure for that field is not
added. Only an agent's stages of the measured kinds
are added, as the Markdown store's stage records count them; a pause and a person's move are shown, not summed.

## States and ids

- `Status` is the truth. A closed state (Finished, Rejected) also closes the GitHub issue as completed or not
  planned, and reopening sets the state back; an issue whose open or closed state disagrees with its `Status` is
  shown with its `Status` and named as a fault.
- A story whose `Status` is empty follows its issues; a set `Status` is given.
- An issue's `Priority` is read as the board's priority of it; an empty one is none, and one outside Highest to
  Lowest is none too and named as a fault. The board names, as on any store, each breach of the stories' rules: an
  open issue past the first state in no story, a story ranked below its open issues, a story with no issue and no
  given state, and a given state its issues do not fit. A breach leaves nothing out and holds no move back.
- An item is known by its tracker reference alone, its repository and GitHub's number, and no field holds another
  id. An old id, of an item carried over from Markdown, is matched to nothing: old commits, velocity rows and task
  records stay under it.
- An issue labeled `story` is a story; every other issue of the project is an issue.
- An open issue of a repository `project.yml` names that is no item of the project, as one made by hand on GitHub is,
  is answered in the board's `unlisted`, apart from the stories and from `outside`, and the window shows it under
  "Issues not on the project", its title as plain text and its link, built from its repository and number, opened
  only when it is HTTPS. It is a story when its issue type is `Story` and it is in the project's own repository, and
  an issue otherwise; the label `story` does not decide it. The list is read at each whole read of the board, at most
  50 pages of 100 across all repositories within 20 s; a repository not reached, or one GitHub refuses to list, keeps
  the list last read for it and is named as a fault. A new or retitled one changes the board's revision.
- Only an item without a valid reference, one of another owner and one of a repository `project.yml` does not name
  are left out and named as a fault; an item without an issue is left out unnamed.
- Every other item a person edited into something the board cannot hold is shown, with a warning that holds work on
  it back, and the issues under it stay under it: an item labeled `story` outside the project's own repository is an
  issue; a parent in another repository, a parent whose parents lead back to the issue, and a parent that is a
  sub-issue itself are cut; a story as a blocker is dropped; and a link to an issue the board does not place - an
  archived one, one listed apart, or one whose id names two items - is cut.
- Two items whose ids are one, as two repositories named alike in other capitals give, are both shown, each warned;
  neither is worked nor moved until one id changes.
- An item with more sub-issues, blockers, labels or field values than one page of GitHub's answer carries is shown
  as far as it was read, warned, and its story says that an issue of it may be missing.
- An issue without a `Status` stands in the workflow's first state, unwarned; no save writes its `Status` until it is
  moved. An issue whose `Status` alone is outside the workflow is listed apart, for a move back into a state of the
  board; one with a warning besides stays on the board, warned for both. A story whose `Status` is outside the
  workflow is shown in that state, warned, with its issues.
- The board's revision is made of the items as GitHub holds them and of the faults, never of the warnings, which
  follow from the items alone.
- A blocker the project does not hold leaves its issue on the board, waiting on it: `waitsOn` names it as
  `owner/repository#number` with whether it is done and its link. A blocker the person's token cannot read is named
  as a wait on something not readable, never as done; so is each blocker GitHub counts but leaves out of the ones it
  names, with no reference.
- Work never starts on an issue while a blocker of it is open: a blocker on the board in an open state, a wait not
  done, or a wait whose state could not be read. A start reads the offered issues' waits once more and refuses on an
  open or unread one; a run already started goes on when a wait opens again.
- A look at the board asks each repository outside the project that a wait stands in, up to 10, whether its issues
  changed, and reads only the waits again when one did; with more of them, or when a look runs out of time, the waits
  are read again once a minute. A repository the token cannot read is asked once after each whole read. A blocker
  counts toward GitHub's 50 blockers of an issue whether the board holds it or not. A fault names the item by its repository and number, never by its title or
  body.
- A story's sub-issue the project's item list lacks is looked up once, by its own project items: when they name the
  project, GitHub's list is taken to lag an add, and the list is read again up to three times a second apart before
  the sub-issue is named as outside the project; when they do not, it is named at once.

## Writes

A move, a worker, a priority and a stage's result are written with the person's own access, without a commit. Right
before it writes, the planner reads the items it writes again and refuses the save, naming the item, when a person
changed one of them since the board was read: a field value, `Status` included, its parent, its sub-issues, its
blockers, its labels, its open state, its title or its body. GitHub takes no condition on a write, so a change made in
the moment between that read and the write is not caught.

Field values go to GitHub in mutations of at most 50 values. GitHub has no transaction: a save of which GitHub took a
part, or a call GitHub took without answering, is refused as partly saved, naming the repositories a part was saved
in, and the board is read anew. An issue's body is written first, a stage's comment next, its field values after
it and its open state after them, so a body or a stage comment GitHub refuses leaves the issue in its state, and the
same result can be handed in again; a stage comment refused after the body was written makes the save partly
saved.
A save sets values and never creates a field or replaces its options.

GitHub gives every option a new id when a field's options are set, and clears every item's value of that field. So
the planner never sets the `Status` options: they are the workflow's states as the person set them on the project,
and an item whose `Status` is an option the workflow does not have is warned or listed apart. A stage's
result overwrites the sections of the body its kind writes and no other byte.

A new issue and an idea are added with the person's own access too, and get no `Id`. Before its first call the
planner checks the revision the window showed, the repositories, every field and option it sets and each body, so
what the project cannot hold is refused with nothing made. A new issue is made in its repository with *What must be
true* as the lead's paragraph followed by the task that proposed it, or, for a text of several paragraphs, as a
section of its own below the task's paragraph; it is added to the project, and given the workflow's
first state in `Status`, its `Size` when given, `Worker`, `Priority` (Medium, or the idea's priority) and the
`Rank` after the last issue of that priority. A *What must be true* holding a heading of the first or second
level or leaving a code block open is refused. An idea's story is made first in the project's own repository, labeled `story`, its gain and
its test in its body, given `Priority` and `Rank`, and no `Status`, so its state follows its issues; then each issue
is made, and they become the story's sub-issues in the idea's order. GitHub drops a label in silence for an account
without push access, so a story made or found without its label stops the write.

A call that makes an issue is never sent twice blindly. When it goes without an answer, the planner reads the
open issues the person made in that repository from a minute before the call on, pull requests, closed issues and
the issues the board or the same write already holds left out, so a duplicate the person closed is never found again: one with the same title and body is taken as made, none makes it once
more, and more than one stops the write, naming them. A call GitHub answered with a failure on its side, or with an
answer that cannot be read, is not sent again. Once a call that makes an issue may have reached GitHub, every stop
is refused as partly saved, naming the repositories, with the references of what GitHub holds in its words, so the
window reads the board anew and never hands the same idea in again. An issue GitHub makes only after the read that
looked for it is not found and is made once more; that moment is the one left.

An edit by hand is written the same way, with the person's own access and after the same check of every item it touches,
the issue and each sub-issue or blocker it links included. An issue's size, estimate, priority and rank are field
values, one mutation, and a value the issue holds is not written again. A story's issue list sets its sub-issues: those
it no longer names are taken out first, the new ones made its sub-issues next, then they are ordered as the list is; an
issue that has a parent on GitHub already is refused, never taken from that parent, and one the story holds only under
`Also needs` in its body keeps its place after the sub-issues and is refused taken out, since the edit leaves the body
as it is; a list that moves it from there, or hangs a new issue in after it, is refused too, since GitHub orders
sub-issues only. An issue's blockers are added and removed as GitHub's "blocked by". GitHub names the issue linked by
its REST id, which the read right before the write answers. A link or an update of an issue GitHub refuses is refused
naming only the status GitHub answered, since GitHub's own words may repeat what was sent; one refused after another was
written is partly saved. A comment is written as the person's text, never one opening with the planner's own marker
`<!-- porelo:`, and the board is not read again after it; a comment GitHub took without answering is partly saved, so it
is not written twice.

A story's or an issue's title and named parts are written by hand the same way. The parts an edit writes are an issue's
line of what it covers, *Why this size*, *What must be true*, *Plan*, *Acceptance*, *Open questions*, *To be checked*
and *Findings*, and a story's *What a user gains* and *The test*; a part may be removed with its lead or heading, but
for what an issue covers and *What must be true*. *Also needs*, a person's own headings, the fields and the stage
comments are never written by it. *What must be true* is written as its lead when its text is one paragraph and as its
section above every other section otherwise, the other form taken out. The new body is built from the body the board
read, part by part, and written only when the body splits into sections unambiguously before and after; a part whose
text would open a part of its own, leave a code block, an HTML comment, a `pre` or a `details` block open, or grow the
body past the 65536 characters GitHub takes is refused before anything is written. Title and body go to GitHub in one
update, which sends neither labels, assignees nor state. The edit is checked against the board's revision, and right
before the update the item is read again and compared, title and body included; the body sent is built from exactly the
body that read confirms, so a section not named keeps every byte. GitHub takes no condition on an issue's update, so a
person's edit made in the one round trip between that read and the update is overwritten; it stays in GitHub's own
history of the body and in the title's rename event.

## Access

The planner reads and writes with the person's own GitHub access, the token Porelo's vault lends it for the forge's
address. The token needs Issues and Contents on the repositories and Projects on the organization. It never stands
in a message, a fault or a log line.

`project.yml` names every repository on one host and under the tracker's owner, by https address
(`https://<host>/<owner>/<repository>`) or by ssh (`<user>@<host>:<owner>/<repository>`). The token is asked for
`https://<host>` either way; with ssh centrals, Git reads the own repository with the person's own login.
