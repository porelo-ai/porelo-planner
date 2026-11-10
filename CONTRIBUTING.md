# Contributing

Changes to porelo-planner follow the project's guide: how an issue is written, how a change is proposed and
reviewed, and what a commit carries. It is one page for every Porelo repository:
[Contributing to Porelo](https://porelo-ai.github.io/porelo-project/contributing/).

## In this repository

- **Building and testing:** JDK 25, the pinned GraalVM. `./mvnw -B -s settings.xml verify` runs the tests, and
  `-Pnative verify` also builds `planner/app/target/porelo-planner`, checks it asks for exactly x86-64 v1 and starts
  it once.
- **The contract** with the window is the planner's OpenAPI description, so a change to what it answers is made
  together with the window.
- **Requirements** are in [issues/README.md](issues/README.md).
