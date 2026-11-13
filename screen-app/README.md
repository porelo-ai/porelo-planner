# Screen App

A test app for the planning [screen](../screen/README.md): the screen alone, without the window, against a mock.
What the window hands the screen is entered by hand, every call the screen makes toward the window is logged with its
time and arguments, and the window's events back - a task started, ended or stopped - are fired by hand. It is run
only from the source; no build builds, packages or publishes it, and Maven runs only its analysis and widget tests,
under the root's profile `all`.

Run it on Linux from this directory, with the Flutter the screen pins:

    flutter run -d linux

- **Against the in-package fake** it needs no process; only there can the board's own events - a new revision, a
  dropped stream, refused saves - be fired by hand.
- **Against a planner started with `--mock`**, the source to test the real contract: start the planner with a runtime
  directory of its own, made with `mkdir -m 700 /tmp/porelo-mock`, as
  `porelo-planner --mock --planner.runtime-directory=/tmp/porelo-mock`, and enter that directory. The app refuses the account's own runtime directory and those under `/run/user`, where the person's real
  planner writes its address and token.
