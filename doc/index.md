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

## The work tools

The same executable indexes and checks a repository's issues and the project's stories when its first argument is
`issues` or `stories`. It then starts no server, opens no port and reads no planner configuration; it answers and
exits. Each command is run from the repository's root:

| Command | What it does |
|---|---|
| `issues index <dir>` | Writes each directory's `README.md` under `<dir>` from its issues' heads. |
| `issues check <dir>` | Checks every head by `issue-1.json` and the workflow, each wait and parent, and each index against what the heads give; writes nothing. |
| `stories index <dir>` | Writes each story's text above `## Data` and the stories' `README.md` from the heads, the issues read from the checkouts beside the project's own. |
| `stories check <dir>` | Checks every story's head, the issues it names in their repositories' indexes, each story's text and the stories' `README.md`; writes nothing. |
| `stories cut <version> <dir> --after <yyyy-mm-dd>` | Prints a release's notes: the stories tested after that day, each linked on the host and under the owner `project.yml` names for the project. |

- **Exit 0** is a pass, with one `OK` line on standard output. **Exit 1** is a refusal: each fault on standard error,
  then their count. **Exit 2** is no answer: something could not be read or written, or the arguments are no command,
  in which case the usage is said.
- **The stories commands read the issue indexes from the checkouts beside the project's own**, named as
  `project.yml` names the repositories, and never over the network; the project's checkout is the nearest directory
  above `<dir>` that holds `project.yml`.
- **A character from a repository that could steer a terminal** - a control, format or line separator character - is
  written as its `\uXXXX` escape in every line the tools say.
- From the packages it is `/usr/libexec/porelo/planner issues check issues`.

## How the window reaches it

- **Only its owner reaches it.** It listens on the loopback address alone, on a port the system chooses, and refuses
  to start on any other address. One planner runs per account and serves every project; a second start refuses
  while the first runs.
- **The address file** is `porelo-planner/planner.json` in the account's runtime directory (`XDG_RUNTIME_DIR`),
  written once the planner listens and deleted when it stops:

      {"port":<port>,"token":"<43 base64url characters>","log":"<the log file's absolute path>"}

  `log` names the planner's log file, which may not exist until the planner first writes; the mock mode keeps no log
  and writes no `log`.
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
| The log, beside the window's | `$XDG_STATE_HOME/porelo/logs/planner.log` | `%LOCALAPPDATA%\porelo\logs\planner.log` |

- Every base is an absolute path read from the environment; a relative path, `..` or, on Windows, a UNC path is
  refused. The property `planner.runtime-directory` overrides the base of the address file, `planner.log.base`
  the base of the log, and `planner.state.base` the base of the kept log level.
- **Only the account reads or writes them.** On Linux each directory is the account's own, of mode `0700`. On Windows
  each directory the planner makes gets one access entry, full control for the account, inherited by what is made in
  it and by nothing from above. A directory of the planner's own, and `%LOCALAPPDATA%`, is accepted only when its owner
  is the account, `SYSTEM` or the local Administrators, no entry grants another principal a right, and it is no link,
  junction or mount; every directory above it only when no other principal may delete, rename or re-permission it.
  Owners and entries are read by security identifier, never by name, and a file system without access lists, such as
  FAT32, makes the planner refuse to start.

- **The log** holds one line per entry - time, level, class and words, never a stack trace: the start, each board
  read with the faults it left out, each save, the tasks offered and each task's report, refused calls by their kind,
  and at DEBUG each Git call by its subcommand, duration and scrubbed failure. Every value from a repository, Git or
  the window has its control characters replaced and is cut to 200 characters, so no value starts a line of its own.
  The window sets the level through `PUT /log/level` - ERROR, WARN, INFO or DEBUG - at once, and the planner keeps it
  in the file `log-level` of its state directory, so it holds from the next start; where none is kept,
  `logging.level.org.fuin.porelo.planner` sets it, INFO by default. Only the planner's own lines reach the file, at
  every level. It rolls over at `planner.log.max-file-size` (10 MB) into `planner.1.log` to `planner.3.log`
  (`planner.log.max-history`), and every file is the account's alone. `porelo/`, which other parts of Porelo share,
  may be readable by others but not writable by the group or others; `logs/` is the account's alone, and a start
  refuses one that is not, naming it. The file is opened only once the planner holds its one-planner lock.

## How it is built

- **One native executable**, built with GraalVM for any x86-64 CPU. Its parts are libraries compiled into it:
  - `core`, the planning rules in plain Java;
  - `store/api`, the store's interface;
  - `store/git`, the store in Git;
  - `app`, the Spring Boot application serving the REST API.
- **The OpenAPI description is the contract with the window.**

See the [decisions](decisions.md) for what holds and why.
