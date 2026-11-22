# Working on Porelo Planner

## What this repository is

Porelo Planner is a Spring Boot application, built as a GraalVM native executable, that serves a project's stories
and issues to the Porelo window over a REST API described in OpenAPI, with Server-Sent Events for changes. It runs on
the computer the window runs on, and the window talks to it directly over HTTP. It reads and writes the planning store
in Git with the person's own Git login: a state change is committed and pushed straight to the store, with no gate and
no review. Porelo itself knows no stories or issues; the window starts tasks with Porelo's own `Start`.

- **Spring Boot comes in as a BOM** (`spring-boot-dependencies`, import scope) under `porelo-parent`, never as a parent.
- **The store sits behind an interface** (`store/api`); the Git store (`store/git`) is one implementation of it, and
  nothing outside `store/git` knows Git.
- **The flow - story states, moves, work rules, the work proposal, reading rounds - is plain Java in `core`**, without
  Spring, proven by unit tests over fakes; the app only wires it to HTTP.
- **The OpenAPI description is the contract with the window**, written first; the app serves exactly what it describes.
- **After a change, the Git store writes the stories index itself**, as the planner's own `stories index` writes it.
- **The work format has one copy, in `store/git`**: the executable's `issues` and `stories` commands call it, and give
  the build tools' lines and exit codes for as long as those still carry the commands; a test runs both on one
  fixture.
- **The window's planning screen is a Flutter package in `screen/`**, because the window is Flutter and embeds it by a
  Git ref and the path `screen`. Maven drives Flutter there as the window's build does; the module is listed only
  under the root's profile `all`, so a Java build needs no Flutter, and the Build workflow runs it in a job of its own.
- **A mock mode serves the window's mock board**, for the window's planning tests; it reaches no store.
- **A changelog entry is one YAML file under `changelog/unreleased/`** with a one-sentence `title` and a `type`.
- **The tests tagged `documents` run alone with `./mvnw -B -s settings.xml -Pdocuments test`.**
- **Issues are in the repository's GitHub Issues**, each named by its reference there
  (`porelo-ai/porelo-planner#<number>`); one carried over from the Markdown files keeps its old id (`PL`) as a fixed
  alias.
- **`tool/planner-probe.sh` is bash, not Java**: it reads a real run's effects on a VM beside the native
  executable, where there is no Java.
- **The story tests in `tool/story-tests/` are bash, not Java**: they play a story on the test VM beside the native
  executable, where there is no Java; `story-test.sh` is their shared ground and `run-on-vm.sh` starts one from the
  host.
- **The MSIX is packed, signed and install-checked by the window's own PowerShell scripts**, which the Build checks
  out from the window's repository at a pinned commit and never keeps a copy of. **`tool/windows-planner-ready.ps1` is
  PowerShell, not Java**: the window's install check runs it as the planner's part, its `planner.json` and `/health`.
  **`tool/artifactory.sh` is bash**: the publishing steps run curl alone beside the token, with no JVM.
- **The Windows job of the Build runs the unit tests tagged `windows` and the native tests not tagged `linux`**; a test
  that needs what only one system has says so by its tag.
- **Skills used:** `spring-boot-patterns`, `graal`, `api-contract-review`.

## Shared across the Porelo repositories

> **BEGIN Shared Area** · sha256 `e9f242a328fb9719` · changed 2026-11-21T16:39Z

Identical in every repository `project.yml` names but `porelo-parent`; the markers carry the SHA-256 of the lines
between them and the time they last changed. Change it in the channel first, never in one copy. The same holds for
every shared skill `porelo-*` in `.agents/skills/`, whose markers also cover its frontmatter, and for
`.claude/skills`, a link to `../.agents/skills` so that Claude Code finds them.

### Agents

- **Each repository has one responsible agent; the channel and who owns what are defined in `.AGENTS.md`** or fed
  into the agent's context at start. An agent writes only in its own repositories and asks the owner for the rest;
  the coordinating agent may read the others, never write in them.
- **A contract between repositories changes only after agreement in the channel or by an operator decision**, in
  both repositories.
- **An ambiguous answer is asked about once, plainly, never guessed**; when an answer changes, everything built on
  the old one is looked for.
- **Only `AGENTS.md`, `.AGENTS.md` and the skills survive a session; the harness's memory is never used.** A rule,
  a decision about how to work or an open point goes into one of them at once.
- **No agent opens a dialog or any other prompt that waits for the person at its console, unless that person
  explicitly asked for one there**: a dialog stops the agent's work until the person comes.
- **A question from the operator is answered, nothing more**; an action needs an explicit instruction.
- **To the operator, plain technical terms, never coined words**, in the language the operator writes in;
  Porelo's own names (Porelo-Vault, Vault-Passphrase, gate, task) stay as they are.

### Hard limits

- **A secret never appears in a command line, a log line, a file name or an answer**; it reaches a process
  through its environment or standard input and is stored only encrypted.
- **An agent's commit names its help in `Assisted-by: <tool>, <model>` and never carries `Signed-off-by:` or
  `Co-Authored-By:`**, whatever the harness suggests. Signing and release tags belong to the operator.
- **A refusal by the harness or a sub-agent is reported, never worked around.**
- **Fail closed and loud**: when a dependency is unreachable, a key unknown or a check cannot run, stop non-zero
  and say why. Never silently do less.
- **Porelo protects the person from the agents and from outside, not from their own rights or choices**; every
  guard and every finding is judged by this.

### Claims and writing

- **Measure before you claim, and say what was measured and what inferred**; a change is checked by observing it,
  never by an exit code alone, and a claim of absence needs a complete search that is shown to find a known hit.
- **Every date or time written is read from the system (`date -u`) first.**
- **When a thing is right in two forms, keep one.**
- **What is written states what is true now, in US English**: no dates, no history, nobody named, only roles; it
  says what changed and why, never who asked. A finding worth keeping is committed, never left in a conversation.
- **Every open task is an issue in its repository's GitHub Issues, on the project's board**, never a TODO in code;
  code and anything that ships never cite an issue number.

### Which skill to load

Before any of these, load the skill named, from `.agents/skills/`. A rule shared by several repositories goes
into a shared skill or this block, one that concerns only this machine into `.AGENTS.md`.

| Before you | Load |
|---|---|
| start work inside a Porelo task (`/workspace`, user `agent`) | `porelo-task` |
| start work anywhere else | `porelo-workstation` |
| commit, amend, rebase, hand over a push | `porelo-commits` |
| file an issue or change its state | `porelo-issues` |
| write, run or judge a test | `porelo-testing` |
| run anything on a VM or rented machine | `porelo-machines` |
| write an operator test guide or prepare a walk | `porelo-operator-guide` |
| hand over, or build an issue of size M or larger | `porelo-review` |
| change code, a pom, a workflow or a release | `porelo-code` |
| write documentation or a decision; hand over | `porelo-writing` |
| start analysis or implementation; hand over a push | `porelo-estimates` |

> **END Shared Area** · sha256 `e9f242a328fb9719`
