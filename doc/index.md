# Porelo Planner

The planner is the part of Porelo that knows stories and issues. The Porelo window shows a project's board from it,
and moves stories and issues through it; Porelo itself only runs tasks, which the window starts.

## What it does

- **It reads the planning store:** the stories of the project repository and the issues, with their sub-issues, of
  each repository, in the states of the project's workflow.
- **It writes state changes straight to the store**, committed and pushed with the person's own Git login, and keeps
  the stories index as the project's tools write it.
- **It tells the window of changes** over Server-Sent Events, whether the planner made them or someone else pushed
  them.

## How it is built

- **One native executable**, built with GraalVM for any x86-64 CPU. Its parts are libraries compiled into it:
  - `core`, the planning rules in plain Java;
  - `store/api`, the store's interface;
  - `store/git`, the store in Git;
  - `app`, the Spring Boot application serving the REST API.
- **The OpenAPI description is the contract with the window.**

See the [decisions](decisions.md) for what holds and why.
