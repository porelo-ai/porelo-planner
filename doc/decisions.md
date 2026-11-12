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

