# Porelo Planner

The planner is the part of Porelo that knows stories and issues. The Porelo window shows a project's board from it,
and moves stories and issues through it; Porelo itself only runs tasks, which the window starts.

## What it does

- **It reads the planning store:** the stories of the project repository and the issues, with their sub-issues, of
  each repository, in the states of the project's workflow.
- **It writes state changes straight to the store**, committed and pushed with the person's own Git login, and writes
  the stories index itself after each change. A move of an issue from a state the workflow lacks, which
  repairs it, adds no move to its stage record in `stats/`, since a record names only states of the workflow.
- **It answers the open issues that are no items of a GitHub-tracked project**, as one made by hand on GitHub is, in
  the board's `unlisted`, which the window shows apart from the board; the Git store and the mock mode answer it
  empty.
- **It tells the window of changes** over Server-Sent Events, whether the planner made them or someone else pushed
  them.

## How it is installed

- **From the same package repositories as Porelo**, as `porelo-planner`, a `.deb` and an `.rpm` for x86-64. The
  executable is `/usr/libexec/porelo/planner`; the window starts it as it needs it, and no service of its own is
  installed. It needs glibc 2.34 or newer and zlib.
- **On Windows, from the same Artifactory** under `porelo-dist-windows/porelo-planner/`, as an MSIX or a ZIP for
  x86-64. The MSIX puts `porelo-planner.exe` on the PATH through an app execution alias; the ZIP is unpacked into a
  folder the person puts on the PATH. It needs Git for Windows and nothing else. Beside them lie `SHA256SUMS`, the
  public certificate the MSIX is signed with, and a Sigstore bundle, against which
  `gh attestation verify <file> --bundle <name>.sigstore.json --repo porelo-ai/porelo-planner` shows that this
  repository's Build made the file.
- **The licenses of what it compiles in** stand in full in `THIRD-PARTY-NOTICES`, under
  `/usr/share/doc/porelo-planner/` from the `.deb`, as a `%license` file from the `.rpm` and beside the executable on
  Windows: every library's own
  license and notice texts, those of what it embeds, the standard text of each license its POM names, and the Java
  runtime's.

## The work tools

The same executable checks a range of commits when its first argument is `check-commits`. It starts no server, opens
no port and reads no planner configuration; it answers and exits. Run it from the repository's root:

| Command | What it does |
|---|---|
| `check-commits <range> [--outside]` | Checks each commit of a Git range against the shared rules on commit messages - a subject of at most 72 characters, only trailers below it, no issue number, told by its shape or as a GitHub reference, nobody named or credited, in the trailers too, a sign-off's name apart, no trailer but `Assisted-by:`, `Signed-off-by:`, one `Porelo-Issue: <owner>/<repository>#<number>` per issue, the one place a number may stand, and Porelo's own, and no closing keyword such as `Fixes` before an issue reference anywhere; it reads nothing from a commit's tree. With `--outside`, each commit also carries its author's `Signed-off-by:`; with `--event` instead of a range, the range and whether it comes from outside are read from the workflow's event. GitHub's and Dependabot's own commits are exempt, except with `--outside`, where a commit's recorded names are the pull request's own word. |

- **Exit 0** is a pass, with one `OK` line on standard output. **Exit 1** is a refusal: each fault on standard error,
  then their count. **Exit 2** is no answer: something could not be read or written, or the arguments are no command,
  in which case the usage is said.
- **`check-commits` reads the Git repository it is run in** and refuses a range that starts with `-`. A log that does
  not split into its commits, because a name, address or message holds Git's separators, is no answer.
- **`issues`, `stories`, `stage` and `check-pushes` are no longer part of the planner.** Each exits with code 2 and
  a message on standard error, before the server starts, with or without a leading `--limits` line.
- **A character from a repository that could steer a terminal** - a control, format or line separator character - is
  written as its `\uXXXX` escape in every line the tools say.
- From the packages it is `/usr/libexec/porelo/planner check-commits HEAD~1..HEAD`.
- **A repository's CI downloads the bare executable** from Artifactory's `porelo-files` repository, pinned by version
  and SHA-256: `porelo-files/porelo-planner/<version>/porelo-planner-<version>-linux-x86_64`. Every push to `main`
  publishes one under its package version, `x.y.z~snapshot.<run>`, and every release under `x.y.z`; the Build's summary
  names the version, the path and the SHA-256. A published executable is never replaced, so a pin holds what it named. A
  change is taken by moving the pin to a newer version and its SHA-256.

## How the window reaches it

- **Only its owner reaches it.** It listens on the loopback address alone, on a port the system chooses, and refuses
  to start on any other address. One planner runs per account and serves every project; a second start refuses
  while the first runs.
- **`--end-with-input` ends it with the window.** The window starts the planner with this option and its standard input
  a pipe the window holds; when that pipe closes, a hard kill of the window included, the planner stops as on any stop
  and deletes its address file. Without the option the planner reads nothing on its input and runs until stopped.
- **The address file** is `porelo-planner/planner.json` in the account's runtime directory (`XDG_RUNTIME_DIR`),
  written once the planner listens and deleted when it stops:

      {"port":<port>,"token":"<43 base64url characters>","log":"<the log file's absolute path>"}

  `log` names the planner's log file, which may not exist until the planner first writes; the mock mode keeps no log
  and writes no `log`.
  With `XDG_RUNTIME_DIR` unset, the runtime directory is `/run/user/<uid>`, as the window reads it. The file is
  readable by the account alone, in a directory of mode `0700`; the planner refuses to start when the runtime
  directory is missing, is a link, or can be read by another account.
- **No call carries the token; every call is signed under it.** A process that binds the port of a planner that
  ended learns no token, and nothing of the person's: before its first call, after a connection error, a timeout, a
  stream it opens again, a `401`, a change of the address file's port or token, and before each new connection, the
  window asks `POST /proof` with 32 random bytes, and the planner answers an HMAC-SHA256 under the token over its port
  and those bytes. A listener whose answer is not that proof is sent nothing more and is shown as not this account's
  planner, or an older one; the window asks again only after a back-off of up to 30 s, or at once when the address
  file changes.
- **Each request is signed in its `Porelo-Signature` header**: an HMAC-SHA256 under the token over the method, the
  raw target, `Host`, `Content-Type`, the body's SHA-256, a time and a nonce, as `openapi/planner.yaml` gives the form.
  A request without one valid signature, with two, with an `Authorization` header, more than 60 s off the planner's
  clock, with a nonce already taken, or with a body larger than 267264 bytes, the longest title and body GitHub takes
  with their JSON (1048576 bytes for a new idea, story or issue, 1052672 bytes for a stage's result), gets `401` with
  `WWW-Authenticate: porelo-planner-request-1`, an empty body and one line in the log; no CORS header is ever sent.
  Every other answer carries `Porelo-Answer`, an HMAC under the token over the request's nonce, the status and the
  body, so the window takes no board or result from a stranger; the changes stream proves its opening this way. Only
  `POST /proof` needs no signature; a proof asked with anything but one challenge gets `400`, empty and unlogged. The
  token is new on every start, the mock mode's too.

- **`GET /health` names the planner the window talks to**: its status, the package version (`x.y.z` or
  `x.y.z~snapshot.<run>`, as the packages carry it) and the commit it was built from, `null` for a build without a
  checkout, so a planner left running from an older install is seen at once.

## The mock mode

`porelo-planner --mock` serves the window's mock board, for the window's planning tests, through the same API and
Server-Sent Events, with no Git and no network. Each project gets its own board in memory; a move saves into it with a
new revision and a change event, one fixed story answers `PartlySaved`, and `POST /mock/reset` restores every board and
forgets the projects given. Only the option turns the mode on, and it refuses to start unless its runtime directory
is set - `XDG_RUNTIME_DIR`, or the property `planner.runtime-directory` - and is not the account's own, so the
person's window never finds a mock.

## Time limits

Every time limit of the planner is its default times one of the four factors of Porelo's limits line, so a slow
network or machine is met by one setting rather than a new build. The window reads the line from Porelo and starts
the planner with it first on the command line, before the mode or work command:
`porelo-planner --limits services=2.0,machines=1.0,polling=1.0,retries=1.0 --end-with-input`.

- **Without the option every factor is 1.0**, so a planner started by hand runs as before. A bad line, the option
  anywhere but first, given twice or written as `--limits=<line>` ends the planner with exit code 64 and
  `--limits: <what is wrong>` before anything starts.
- **`services`**: an answer from GitHub, a Git host or Porelo's own executable; **`machines`**: a git or PowerShell
  call and a save's wait for the project; **`polling`**: how often a followed project is fetched or asked whether it
  changed; **`retries`**: how often a failed read, write or push is made again, and the pause before each try.
- **The duration properties follow their factor too**: `planner.github.read-silence` times `services`,
  `planner.poll-interval` and `planner.github.poll-interval` times `polling`; a value of zero or less of these, or of
  the fixed `planner.keep-alive`, is refused at start, in the mock mode too.
- **Porelo's own executable is handed the same line** before its verb, for its task records and for its credential
  helper.
- **Protocol values stay fixed**: GitHub's rate-limit window, the window in which a change is taken as the
  planner's own, sizes and counts.

## Where it keeps its files

| What | Linux | Windows |
|---|---|---|
| The address file and its lock | `$XDG_RUNTIME_DIR/porelo-planner/planner.json` | `%LOCALAPPDATA%\porelo-planner\planner.json` |
| The work record | `$XDG_STATE_HOME/porelo-planner/<project>/` | `%LOCALAPPDATA%\porelo-planner\state\<project>\` |
| The clones | `$XDG_CACHE_HOME/porelo-planner/<project>/` | `%LOCALAPPDATA%\porelo-planner\cache\<project>\` |
| The copies of tracked boards | `$XDG_CACHE_HOME/porelo-planner/_boards/` | `%LOCALAPPDATA%\porelo-planner\cache\_boards\` |
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
- **The window's planning screen reads the address file by the same rules** on both systems before it signs a call
  under the token: on Windows it asks Windows PowerShell of the system's own directory for the owners and entries from the
  drive's root down to `planner.json`, and refuses a `%LOCALAPPDATA%` reached through a link, a substituted drive or a
  mapped share.

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
- **Whether the Build may publish and sign** is read by starting the workflow *Publishing preconditions* by hand: one
  pass or fail line for every secret and variable the publish and signing steps use, for reading, uploading and
  deleting in Artifactory's `porelo-files`, and for the Windows signing key opening as the pinned signer.

See the [decisions](decisions.md) for what holds and why.
