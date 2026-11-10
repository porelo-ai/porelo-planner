# Decisions

What holds and why: one row per decision, and its full text below.

| Covers | What holds |
|---|---|
| Store | [Git is a plain store](#git-is-a-plain-store) |
| Build | [One native executable of four parts](#one-native-executable-of-four-parts) |
| License | [Everything the executable links is under the LGPL or more permissive](#everything-the-executable-links-is-under-the-lgpl-or-more-permissive) |
| Work | [A story's work is recorded on the computer it runs on](#a-storys-work-is-recorded-on-the-computer-it-runs-on) |
| Work | [The window tells the planner of its tasks](#the-window-tells-the-planner-of-its-tasks) |

## Git is a plain store

A state change is committed and pushed straight to the planning repository with the person's own Git login, with no
gate and no review, as a database would take it. The store sits behind an interface, so the rest of the planner knows
no Git.

## One native executable of four parts

The planner is one GraalVM native executable. `core` holds the planning rules without Spring, `store/api` the store's
interface, `store/git` its Git implementation and `app` the Spring Boot application; the first three are libraries
compiled into the last. So the rules are proven by fast unit tests, and everything the executable holds must work
without reflection the native image does not know of.

## Everything the executable links is under the LGPL or more permissive

The planner is LGPL-3.0-or-later. A native executable compiles every library it uses into itself, so a library under
the GPL would make the whole executable a work under the GPL. Each library it links is therefore under the LGPL or a
more permissive license, the stories library included.

## A story's work is recorded on the computer it runs on

Which tasks belong to a story, the reading rounds used and allowed, the findings and every reader's answer are kept
in a file of the planner's own on the computer the tasks run on, not in the Git store. Only the states go into the
store. A lost record leaves every issue in its right state, without its counts and findings; findings never become
public text in a repository, and the store's history holds no commit per task end.

## The window tells the planner of its tasks

The planner talks to no Porelo. The window, which starts a story's tasks with Porelo's `Start` and watches them
already, tells the planner of every change of such a task - started, ended, stopped - and the planner judges what
follows from it. A change told twice is taken once.
