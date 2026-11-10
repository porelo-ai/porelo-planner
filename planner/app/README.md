# App

The Spring Boot application that serves the planner over HTTP on the loopback address, built into the one native
executable `porelo-planner` with `-Pnative`. It wires the [core](../core/README.md) and the
[Git store](../store/git/README.md) to HTTP and holds no planning rule of its own.
