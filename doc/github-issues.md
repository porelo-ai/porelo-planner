# Stories and issues in GitHub Issues

A project whose `planner.yml` names GitHub Issues as its tracker keeps its stories and issues in a GitHub project
instead of Markdown files. This page says where each part of a story and an issue stands there. The workflow and
the [work rules](task-roles.md) stay files in the project's own repository; for a file it does not keep, the planner
takes the workflow or the work rules it carries.

## What stands where

| Porelo | GitHub |
|---|---|
| A story | An issue in the project's own repository of the issue type `Story` |
| A story's issues | Its sub-issues, in their own repositories, in the story's order |
| An issue in a second story | Listed under *Also needs* in that story's body; GitHub allows one parent |
| A Porelo sub-issue | A sub-issue of its issue, in the issue's repository, with its own number; never under another sub-issue |
| `blockedBy` | GitHub's issue dependency, "blocked by", on an issue of the project |
| `waitsOn` | GitHub's "blocked by" on an issue the project does not hold, such as one of another owner's repository |
| The id | The tracker reference alone, `<repository>#<number>`: the repository the issue lives in, lowercased, and GitHub's number |
| The state | The project's own field `Status`, whose options are the workflow's states in order |
| Priority | The organization's single-select issue field `Priority` |
| Size, risk, worker | The project's single-select fields `Size`, `Risk`, `Worker` |
| The order within a priority | The number field `Rank`, sorted by the planner |
| What is true now | The issue's body, as sections |
| The course in time | One comment per stage |
| The sums of the stages | The number fields `Estimate`, `Minutes`, `VM minutes`, `Tokens`, `Stages` |

The planner adds 9 fields to the project. A GitHub project holds at most 50, 13 of them GitHub's own.

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
stage, the result, the actor, the model and the effort. A summed stage's `base` holds the issue's Minutes, VM minutes,
Tokens and Stages as numbers before the stage was added to them. The base supports recovery; the stage's own figures
are kept only in the project fields. The key names the task and the stage, so a stage written twice counts once.

A stage ends at the comment's `createdAt`, the second GitHub wrote it. A person's move is no comment: GitHub's
timeline shows each `Status` change with its time, its actor and the states before and after.

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

The comments are the course of the work, the record a person reads; the board never reads them, and shows the number
fields as GitHub holds them. A stage's save adds its minutes, VM minutes, new tokens and one stage to the fields as the
board read them, in the same call as its state, and refuses the save, naming the issue, when GitHub's value changed
since. Its comment is written before the fields and names, as `base`, the four fields as they stood before the stage
was added to them. A field without a value counts as 0, and a value a person set stays the base later stages add to,
with no warning; the planner never sums the comments. A field the board reads as none, or a sum that would pass the
field's bound, is never written: the save is refused, naming the issue and the field. The bounds are one rule for the
board and the save: `Minutes` and `VM minutes` from 0 to 10,000,000, `Tokens` and `Stages` whole numbers from 0. A
stage whose comment, found by its key, already stands is not written again, and its figures are added only to a field
that still stands at the comment's base, so a result handed in again after a save cut off between the comment and the
fields counts the stage once; with a comment that names no base, none is added again. A summed field that no longer
stands at the base when the result is handed in again counts the stage as added there, so a person's edit of it
between the two tries, or another stage of the issue saved in between, is kept and the stage's figure for that field
is not added; such a field, one the board reads as none too, does not refuse the save. A stage comment that carries
its own figures is read with those figures ignored. Only an agent's stages of the measured kinds are added, as the
Markdown store's stage records count them; a pause and a person's move are shown, not summed.

## States and ids

- `Status` is the truth: a board item's state never comes from GitHub's open or closed. A move into a closed state
  (Finished, Rejected) also closes the GitHub issue as completed or not planned, and a move out of a closed state or
  of one the workflow lacks reopens it; a story without a `Status` counts as standing in the state its issues give
  it. An issue whose open or closed state disagrees with its `Status` is shown with its `Status` and warned.
  - One GitHub has closed under an open state is neither proposed nor started; a move or an edit may still name it,
    so a move into a closed state sets it right. A move from one open state to another, a stage result's included,
    leaves it closed, so a close by a person or a merge is never undone unseen.
  - One GitHub has open under a closed state holds nothing: it counts as done, and the issues it blocks may start.
- A story whose `Status` is empty follows its issues in states of the workflow; a set `Status` is given. When its
  issues all stand outside the workflow, or those inside it are all closed while another stands outside it, it
  takes no state, warned `NoState`, as the Git store reads it.
- An issue's priority is read from the organization's issue field `Priority`: Urgent is Highest, and High, Medium
  and Low keep their words. An empty one is none, and any other option is none too, warned `UnknownPriority`,
  and sorted as Medium. A story without a `Priority` is shown as Medium and warned `NoPriority`, one with another
  option as Medium and warned `UnknownPriority`. A project field named `Priority` is neither read nor required.
- GitHub's field has no option for Lowest, so a priority Lowest is refused before any call, as a priority the
  planner does not know is: an edit of an issue's fields or a story's priority as `MoveNotAllowed`, a new issue as
  `IssueNotAllowed` and an idea or a story made from issues as `IdeaRefused`, each HTTP 422.
- A priority is written through GitHub's REST update of the issue, which replaces every issue field value it
  holds: the planner sends the other values back as they stand, and refuses the save before any write when one
  of them is of a kind it cannot send back. A priority the issue holds already sends nothing. A new issue is made
  with its priority; one GitHub answers without it is partly saved and finished by the next try.
- Shown with a warning that holds no work: a story under an issue, without it; a story's line under *Also needs* that
  names no issue the board shows; a story's sub-issue outside the project; a body longer than GitHub takes, without
  it; a `Size` the planner does not know, as none; and a number field that holds no number in its range, as none,
  the warning naming the field. The board names, as on any store, each breach of the stories' rules: an
  open issue past the first state in no story, a story ranked below its open issues, a story with no issue and no
  given state, and a given state its issues do not fit. A breach leaves nothing out and holds no move back.
- An item is known by its tracker reference alone, its repository and GitHub's number, and no field holds another
  id. An old id, of an item carried over from Markdown, is matched to nothing: old commits, velocity rows and task
  records stay under it.
- An item of the issue type `Story` is a story; every other item of the project is an issue, and the label `story`
  is neither read nor written. Issue types exist only on an organization's repositories, so a project of a personal
  account holds no story. An item of the type `Story` outside the project's own repository is shown as an issue,
  warned, with the issues under it.
- An open issue of a repository `project.yml` names that is no item of the project, as one made by hand on GitHub is,
  is answered in the board's `unlisted`, apart from the stories, and the window shows it under
  "Issues not on the project", its title, its author's login and how the author stands to the repository as plain
  text and its link, built from its repository and number, opened only when it is HTTPS. A login of no login's form
  and an association GitHub does not name are left out, and the window shows none; neither stands in a fault or a
  log line. It is a story when its issue type is `Story` and it is in the project's own repository, and
  an issue otherwise; the label `story` does not decide it. The list is read at each whole read of the board, each
  repository's to its last page, all within 20 s times the factor `services`; a repository not reached, one GitHub
  refuses to list, or one whose next page GitHub answers as the page before keeps the list last read for it and is
  named as a fault. A new or retitled one changes the board's revision.
- Only an item without a valid reference, one of another owner and one of a repository `project.yml` does not name
  are left out and named as a fault; an item without an issue is left out unnamed.
- Every other item a person edited into something the board cannot hold is shown, and the issues under it stay
  under it. Most are warned so that no work, move or edit starts on them: an item of the issue type `Story` outside
  the project's own repository is an issue; a parent outside the project, a parent in another repository, a parent whose
  parents lead back to the issue, and a parent that is a sub-issue itself are cut; a story as a blocker is dropped;
  and a link to an issue the board does not place - an archived one GitHub has open, an archived parent, or one
  whose id names two items - is cut. A story under another issue is shown without that parent and
  named as a fault only.
- A blocker GitHub has closed and archived, as its own "Auto-archive items" workflow does with finished work, is a
  wait that is done, as a closed blocker outside the project is.
- Should two items ever give one id, both are shown, each warned, and neither is worked, moved or edited until one
  id changes. GitHub keeps one repository name per owner whatever its case, so this is a guard, not a case a person
  is expected to make. An archived item of an id a live item holds is no second copy: the board and every write take
  the live one.
- No edit adds an issue held back to a story or as a blocker, nor takes one out; one kept in a story's list may still
  be reordered.
- An item with more sub-issues, blockers, labels or field values than one page of GitHub's answer carries is shown
  as far as it was read, warned, and its story says that an issue of it may be missing.
- An issue whose `Worker` or `Risk` is an option the planner does not know is shown as an agent's and at the highest
  risk, warned, and held: no work starts on it until a person sets an option the planner knows on GitHub. A
  sub-issue held for its `Risk` still raises the care of a task over its parent to the highest.
- Every issue under a held issue, at any depth, is held with it, its own warnings or none. A sub-issue may still be
  moved while its parent is held for its `Status` alone, as the parent may be; under a parent held for anything
  else, its move is refused too, even when its own `Status` is outside the workflow.
- A story the board cannot show whole - an issue of it outside the project or left out, an `Also needs` line that
  names nothing, or one read past one page - is never moved to a completed state; it may still be rejected. Without
  a `Status`, it never follows its issues into a closed state: when every issue shown is closed, it stands in the
  workflow's last open state.
- An issue without a `Status` stands in the workflow's first state, unwarned; no save writes its `Status` until it is
  moved. An issue or a story whose `Status` is outside the workflow is shown in that state under its story, or with
  its issues, warned `StateOutsideWorkflow`, and warned for any other fault besides; no work starts on it, and a move
  into a state of the workflow repairs it.
- The board's revision is made of the items as GitHub holds them, whether their labels or field values lie past one
  page among them, and of the faults, never of the warnings, which follow from the items alone.
- A blocker the project does not hold leaves its issue on the board, waiting on it: `waitsOn` names it as
  `owner/repository#number` with whether it is done and its link. A blocker the person's token cannot read is named
  as a wait on something not readable, never as done; so is each blocker GitHub counts but leaves out of the ones it
  names, with no reference.
- Building never starts on an issue while a blocker of it is open: a blocker on the board neither closed nor in
  review, a wait not done, or a wait whose state could not be read. Only the building work is held back; analysis,
  estimation and the other work move a blocked issue with its story. A start into the building work reads the offered
  issues' waits once more and refuses on an open or unread one; a run already started goes on when a wait opens again.
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
and an item whose `Status` is an option the workflow does not have is warned. A stage's
result overwrites the sections of the body its kind writes and no other byte.

A new issue and an idea are added with the person's own access too, and get no `Id`. Before its first call the
planner checks the version the window saw of the story it names, if one, the repositories, every field and option it sets and each body, so
what the project cannot hold is refused with nothing made. A new issue is made in its repository with *What must be
true* as the lead's paragraph followed by the task that proposed it, or, for a text of several paragraphs, as a
section of its own below the task's paragraph, with its `Priority` (Medium, or the idea's priority); it is added to
the project, and given the workflow's first state in `Status`, its `Size` when given, `Worker` and the `Rank` after
the last issue of that priority. A *What must be true* holding a heading of the first or second
level or leaving a code block open is refused. An idea's story is made first in the project's own repository, of the
issue type `Story`, its gain and its test in its body, given `Priority` and `Rank`, and no `Status`, so its state
follows its issues; then each issue is made, and they become the story's sub-issues in the idea's order. GitHub drops
an issue type in silence for an account that may not set one, so a story made or found without its type stops the
write.

An issue offered under "Issues not on the project" is added with the person's own access, in one save. The planner
checks the version the window saw of the issue and that the board still offers it, then reads the issue right before the
write: one GitHub no longer holds is no longer offered; one that is closed, moved to another repository or already an
item of the project, as when a person added it on GitHub meanwhile, is refused as changed (ItemChanged), and none of these writes
anything. One the board would show only as a warned issue no work starts on is refused with nothing written, naming its
warning: of the type `Story` outside the project's own repository (`StoryOutsideOwnRepository`); under a parent that is
no item of the project (`ParentOutsideProject`) or that the board does not place, as one archived, listed apart outside
the workflow or left out (`DependsOnUnplaced`); under a sub-issue (`SubIssueOfSubIssue`); under an issue of another
repository (`ParentInOtherRepository`); blocked by a story (`BlockedByStory`) or by an item the board does not place
(`DependsOnUnplaced`); and a story that blocks an item of the project, which the board would then show only as a warned
issue (`BlockedByStory`). One blocked by more issues than one read names is refused too, since not all its blockers can
be checked. A story of another repository is not offered at all, since as a project item it stands on the board only as
a warned issue no work starts on; a note among the faults names it. An issue is then added to the project and given the
workflow's first state in `Status` and `Worker` Manual, so no agent takes an issue nobody chose, the `Priority` it
holds, or Medium when it holds none, and the `Rank` after the last issue of that priority, or of Medium for an option
the planner does not know; a story is given `Priority` and `Rank` only, so its state follows its issues. An add
GitHub refused writes nothing and is refused as such; once the add stands, or may stand after a call
GitHub left unanswered, a stop is partly saved, naming the issue, since it then stands on the project outside the
workflow until it gets its `Status`. A workflow of GitHub's own project that sets a `Status` on an added item writes
after the planner and is seen at the next read. A person who adds the same issue on GitHub between the planner's read
and its add has values the add then writes over; that moment is the one left. An issue GitHub no longer gives is refused
as no longer offered, and the board is read whole, so the offer drops it at once. The Git store adds none and answers
the add as not offered.

A look reads an item again when its own `updatedAt` or its issue's moved. GitHub moves an issue's `updatedAt` when its
type is set, changed or cleared, so the next look sees a story made or unmade by its type.

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
update, which sends neither labels, assignees nor state. The edit is checked against the version the window saw of the item, and right
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
