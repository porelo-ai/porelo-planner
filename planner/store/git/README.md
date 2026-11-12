# Git store

The planning store in Git: the planner's own clones of the project's repositories, read and written with the `git`
command line and the person's own Git login. It implements the [store API](../api/README.md) and is the only part of
the planner that knows Git. Its `pushes` package alone reads a forge, GitHub's repository activity for
`check-pushes`; the work format in `stories` reaches no network.
