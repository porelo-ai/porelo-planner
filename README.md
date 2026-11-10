# Porelo Planner

<img src="doc/images/crazy-chicken.svg" width="350" alt="Crazy chicken - just hatched">

> **Crazy chicken - just hatched.** Interfaces, formats and even the purpose may change any day. Use it only to try
> ideas, never to build on.

Porelo Planner serves a project's stories and issues to the Porelo window over a REST API described in OpenAPI, with
Server-Sent Events for changes. It runs as a GraalVM native executable on the computer the window runs on, and reads
and writes the stories and issues in Git with the person's own Git login. Its [parts](planner/README.md) are compiled
into that one executable.
See the [documentation](doc/index.md), [contributing guide](CONTRIBUTING.md), and [security policy](SECURITY.md).
