# Decisions

What holds and why: one row per decision, and its full text below.

| Covers | What holds |
|---|---|
| Store | [Git is a plain store](#git-is-a-plain-store) |
| Build | [One native executable of four parts](#one-native-executable-of-four-parts) |
| License | [Everything the executable links is under the LGPL or more permissive](#everything-the-executable-links-is-under-the-lgpl-or-more-permissive) |
| Work | [A story's work is recorded on the computer it runs on](#a-storys-work-is-recorded-on-the-computer-it-runs-on) |
| Work | [The window tells the planner of its tasks](#the-window-tells-the-planner-of-its-tasks) |
| Access | [One planner per person](#one-planner-per-person) |
| Store | [The planner keeps its own clones](#the-planner-keeps-its-own-clones) |
| Store | [The store owns the checks and records of its stories and issues](#the-store-owns-the-checks-and-records-of-its-stories-and-issues) |
| Access | [The window never sends the planner's token](#the-window-never-sends-the-planners-token) |
| Access | [A lent login is Porelo's alone](#a-lent-login-is-porelos-alone) |
| Access | [The planner ends with the window that started it](#the-planner-ends-with-the-window-that-started-it) |
| Work | [A handed-in idea is saved once per offer, not per request](#a-handed-in-idea-is-saved-once-per-offer-not-per-request) |
| Work | [A story's state follows its issues](#a-storys-state-follows-its-issues) |
| Build | [Windows ships beside Linux, never holding it back](#windows-ships-beside-linux-never-holding-it-back) |
| Work | [A move is saved at once, warned where it can do harm](#a-move-is-saved-at-once-warned-where-it-can-do-harm) |
| Store | [A save answers once its commits stand locally](#a-save-answers-once-its-commits-stand-locally) |

## Git is a plain store

A state change is committed and pushed straight to the planning repository with the person's own Git login, with no
gate and no review, as a database would take it. The store sits behind an interface, so the rest of the planner knows
no Git.

The planner never asks for a login. Git runs without a terminal prompt or a password dialog; a missing or expired
login is refused at once and shown in the window, since a background process that waits on a prompt nobody sees
hangs the board.

## One native executable of four parts

The planner is one GraalVM native executable. `core` holds the planning rules without Spring, `store/api` the store's
interface, `store/git` its Git implementation and `app` the Spring Boot application; the first three are libraries
compiled into the last. So the rules are proven by fast unit tests, and everything the executable holds must work
without reflection the native image does not know of.

## Everything the executable links is under the LGPL or more permissive

The planner is LGPL-3.0-or-later. A native executable compiles every library it uses into itself, so a library under
the GPL would make the whole executable a work under the GPL. Each library it links is therefore under the LGPL or a
more permissive license.

## A story's work is recorded on the computer it runs on

Which tasks belong to a story, the reading rounds used and allowed, the findings and every reader's answer are kept
in a file of the planner's own on the computer the tasks run on, not in the Git store. Only the states go into the
store. A lost record leaves every issue in its right state, without its counts and findings; findings never become
public text in a repository, and the store's history holds no commit per task end.

A run ends with its story: a run whose story is gone from the store is dropped, and a story saved anew under an old
ID starts without it. Accepted risk: a story written outside the planner under a reused ID, while the planner never
read the board without the old one, still takes over the old run. It stays until such a case is seen; keeping the
story's identity from the store with its run would close it.

## The window tells the planner of its tasks

The planner talks to no Porelo. The window, which starts a story's tasks with Porelo's `Start` and watches them
already, tells the planner of every change of such a task - started, ended, stopped - and the planner judges what
follows from it. A change told twice is taken once.

## One planner per person

A planner serves one person, on their own computer or under their own account on a host, with their own Git login,
clones and token; several people on one host each start their own. A planner shared by every user of a host would
have to keep each user's Git login or write under an account of its own, which no longer shows who moved a story, so
it stays an idea until that is decided.

## The planner keeps its own clones

The planner reads and writes a project through clones of its own, not through the read-only checkout a task gets.
That checkout is assembled only when a task starts, on the machine the task runs on; the planner must commit and push
a move or a new idea with the person's own Git login, a board read through the window would need a running window and
a reachable machine, and on Windows or a Mac there is no such machine at all. Only a discussion task reads the
project's repositories as one tree, since an agent needs one.

## The store owns the checks and records of its stories and issues

The checks of issue and story heads, of the issue indexes and of the links between issue pages belong to the store
that keeps them; a store kept in an outside tool checks as that tool does. Recording an issue's stages belongs to the
store too. The velocity does not: `core` computes it from Porelo's own record of each task over the issues the store
answers, so every store has it alike. Every repository's issues are checked against the project's one workflow, and
which issue prefixes a repository owns stays in `project.yml`, since the prefixes mark the project and not only its
issues.

## The window never sends the planner's token

A planner that ended leaves its port free, and another account's process may bind it on the loopback address. So the
token never travels: each request is signed under it, each answer proves itself under it, and before the window sends
anything on a new connection the listener proves it holds the token. A listener that cannot is sent nothing more.

Accepted risks:

- **A slow body holds a thread.** The planner reads a body of up to 128 KiB before it checks the signature, so
  another local account can hold a thread per slow connection until the connection times out. The exposure is the
  planner's availability on the loopback address alone, never the token or the person's data; a planner reachable
  from more than the loopback address would change the answer.
- **One connection's opening.** The proof and the request travel on different connections, so a listener swapped in
  between them gets one request: its signature but never the token, with the project's name and the body. Its
  answer is refused for want of a proof. It stays because the window is kept to one connection's opening; a proof
  bound to the request's own connection would change the answer.
- **The changes stream's events are unproven.** Only the stream's opening is proven; an event cannot be forged
  without first taking the connection, and every reopen proves the planner again.

## A lent login is Porelo's alone

When the window names Porelo's executable in `PORELO_EXECUTABLE` and the planner lends Porelo's login, only Porelo's
credential helper is asked for a project's https centrals: each call clears the helpers collected before it for that
address, names Porelo's with `useHttpPath`, follows no redirect and writes no trace. The person's own helpers no
longer apply to those addresses, so no other helper sees the token, and a central that redirects is refused until
`project.yml` names its new address. The executable and every directory above it must be writable by nobody but the
account or the system; a check that cannot run lends nothing. An ssh central gets no helper, and without the variable
the person's own Git configuration is the only one.

## The planner ends with the window that started it

A planner the window started ends when the window's end of its input pipe closes, a hard kill included, so a planner
of an older install never keeps serving a newer window. A planner started by hand reads nothing on its input and runs
until it is stopped.

## A handed-in idea is saved once per offer, not per request

The window hands a discussion's idea in with the offer it belongs to, and the planner saves one story per offer, so a
retried hand-in makes nothing twice. Accepted risk: an issue already pushed in another repository when the own
repository's save is dropped stays there as an ordinary issue in Idea, with no story naming it. It costs a person one
issue to reject, and stays because taking a pushed commit back is a force push; a store that saves all repositories
at once would change the answer.

## A story's state follows its issues

A story shows the state the stories index gives it from its issues; the planner states none of its own. A person may
give a story another state by a confirmed move, and the board then marks it as given and names each issue that does
not fit. A story is set Finished only once every issue of it stands in Review, Finished or Rejected, and that move
takes no issue along, so a story is never closed over open work; only a person sets it. An issue move in a story
whose run has started is refused as `WorkRunning` unless the person confirms stopping that run.

## Windows ships beside Linux, never holding it back

The Windows executable is built and tested in a job of its own from the same commit as the Linux packages, and
published by a job that needs both, so a red Windows job never holds back the Linux packages; a rerun of the failed
jobs publishes the Windows files of the same run. Only the tests tagged `windows` run there, since they name what only
Windows shows; the rest of the suite runs on Linux. The MSIX is packed, signed and install-checked by the window's own
scripts, checked out at a pinned commit, never by a copy kept here.

Accepted risk: two runs of one release version at once, such as a tag made again while its first build still runs,
can both find a file absent and both upload it, since the check comes before the upload, which carries no condition.
The Linux publish has the same exposure. A publishing token that cannot overwrite a stored file would close it.

## A move is saved at once, warned where it can do harm

A move on the board is saved as it is made, with no Save and no Undo; a mistaken move is moved back by hand. Since a
save reaches the store with no gate and no review, each kind of move first warns where it can do harm - running agent
tasks to stop, issues of other repositories moving along, states skipped, a story's issues not fitting its target -
and goes on only once the person confirms. A move into Finished or Rejected is not asked about as such, since it can
be moved back. A move back keeps the story's run as its history, so a new start counts on from it.

## A save answers once its commits stand locally

A save answers once its commits stand in the planner's own clones, held by a ref of the planner's own so a restart
loses none; the pushes follow in the background, the other repositories before the project's own. A push rejected
because the remote moved is replayed on the fetched head; one that conflicts drops the save and the board names what
was dropped. A save of several repositories is not dropped all or none: what another repository took is on its
remote and stays, since unmaking a pushed commit would be a second write over the remote.
