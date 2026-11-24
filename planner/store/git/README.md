# Git store

The planning store in Git: the planner's own clones of the project's repositories, read and written with the `git`
command line and the person's own Git login. It implements the [store API](../api/README.md) and is the only part of
the planner that knows Git. The work format in `stories` reaches no network; its index writers are internal to the
store, while its commit rule serves the executable's `check-commits` command.
