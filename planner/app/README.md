# App

The Spring Boot application that serves the planner over HTTP on the loopback address, built into the one native
executable `porelo-planner` with `-Pnative`. It wires the [core](../core/README.md) and the
[Git store](../store/git/README.md) to HTTP and holds no planning rule of its own. With `issues`, `stories` or
`check-commits` as its first argument, the executable runs the [work tools](../../doc/index.md#the-work-tools) over the
Git store's work format instead, and starts no server; `exec:exec@planner -Dplanner.args=...` runs them on the JVM
after an install.

Run from the source with restarts on every compile, from the repository's root:
`./mvnw -s settings.xml -Pdev -pl planner/app -am compile spring-boot:run`. A second terminal compiles a changed module
with `./mvnw -o -q -s settings.xml -pl <module> compile`, and the planner restarts with it on new port and token; the
window reads them when it reaches the planner again.
