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

## How it is installed

- **From the same package repositories as Porelo**, as `porelo-planner`, a `.deb` and an `.rpm` for x86-64. The
  executable is `/usr/libexec/porelo/planner`; the window starts it as it needs it, and no service of its own is
  installed. It needs glibc 2.34 or newer and zlib.
- **The licenses of what it compiles in** stand in full in `THIRD-PARTY-NOTICES`, under
  `/usr/share/doc/porelo-planner/` from the `.deb` and as a `%license` file from the `.rpm`: every library's own
  license and notice texts, those of what it embeds, the standard text of each license its POM names, and the Java
  runtime's.

## How the window reaches it

- **Only its owner reaches it.** It listens on the loopback address alone, on a port the system chooses, and refuses
  to start on any other address. One planner runs per account and serves every project; a second start refuses
  while the first runs.
- **The address file** is `porelo-planner/planner.json` in the account's runtime directory (`XDG_RUNTIME_DIR`),
  written once the planner listens and deleted when it stops:

      {"port":<port>,"token":"<43 base64url characters>"}

  With `XDG_RUNTIME_DIR` unset, the runtime directory is `/run/user/<uid>`, as the window reads it. The file is
  readable by the account alone, in a directory of mode `0700`; the planner refuses to start when the runtime
  directory is missing, is a link, or can be read by another account.
- **Every call carries the token** as `Authorization: Bearer <token>`, on every path. A call without it, or with
  another, gets `401` with `WWW-Authenticate: Bearer` and an empty body, and no CORS header is ever sent. The token
  is new on every start.

## Where it keeps its files

| What | Linux | Windows |
|---|---|---|
| The address file and its lock | `$XDG_RUNTIME_DIR/porelo-planner/planner.json` | `%LOCALAPPDATA%\porelo-planner\planner.json` |
| The work record | `$XDG_STATE_HOME/porelo-planner/<project>/` | `%LOCALAPPDATA%\porelo-planner\state\<project>\` |
| The clones | `$XDG_CACHE_HOME/porelo-planner/<project>/` | `%LOCALAPPDATA%\porelo-planner\cache\<project>\` |

- Every base is an absolute path read from the environment; a relative path, `..` or, on Windows, a UNC path is
  refused. The property `planner.runtime-directory` overrides the base of the address file.
- **Only the account reads or writes them.** On Linux each directory is the account's own, of mode `0700`. On Windows
  each directory the planner makes gets one access entry, full control for the account, inherited by what is made in
  it and by nothing from above. A directory of the planner's own, and `%LOCALAPPDATA%`, is accepted only when its owner
  is the account, `SYSTEM` or the local Administrators, no entry grants another principal a right, and it is no link,
  junction or mount; every directory above it only when no other principal may delete, rename or re-permission it.
  Owners and entries are read by security identifier, never by name, and a file system without access lists, such as
  FAT32, makes the planner refuse to start.

## How it is built

- **One native executable**, built with GraalVM for any x86-64 CPU. Its parts are libraries compiled into it:
  - `core`, the planning rules in plain Java;
  - `store/api`, the store's interface;
  - `store/git`, the store in Git;
  - `app`, the Spring Boot application serving the REST API.
- **The OpenAPI description is the contract with the window.**

See the [decisions](decisions.md) for what holds and why.
