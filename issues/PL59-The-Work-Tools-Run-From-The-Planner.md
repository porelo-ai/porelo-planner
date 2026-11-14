# PL59 — The Work Tools Run From The Planner

```json
{
  "$schema": "issue-1.json",
  "id": "PL59",
  "title": "The Work Tools Run From The Planner",
  "state": "Review",
  "size": "M",
  "priority": "High",
  "rank": 1,
  "blockedBy": [],
  "covers": "A repository's build indexes and checks its issues and stories with a command line of the planner, so no repository needs the build tools for its own work"
}
```

**Size:** M - one command line in the planner's own executable over code that is already there, and one more file
the Build publishes; no new module, no Maven Central and no contract another repository builds against in Java.

**Estimate:** 67 min, the measured median of M. Uncertain: the reachability metadata the commands need in the native
image, and the published name of the executable, which the build tools' agent agrees.

**What must be true.** Every repository indexes and checks its issues and stories with the planner's own executable,
with the same results and exit codes as the build tools' `issues` and `stories` commands give today; a repository's
CI downloads that executable pinned by version and sha256, and nothing of the planner is published as a library.

## Plan

What was built:

- **The command line in the executable.** `PlannerApplication.main` hands its arguments to `WorkCommands` in `app`
  when the first one is `issues` or `stories`, and exits with its code before Spring starts, so a check starts no
  server, opens no port and reads no planner configuration. `issues index|check <dir>`, `stories index|check <dir>`
  and `stories cut <version> <dir> --after <yyyy-mm-dd>` call the Git store's `IssuesIndex`, `StoriesIndex` and
  `IssueSource.local`, with the build tools' arguments, output lines and exit codes (0, 1, 2), and a usage line with
  exit 2 for anything else; `issues convert` is not carried over. The work format stays in `planner/store/git`, one
  copy.
- **The stories commands read the issue indexes from the checkouts beside the project's own**, found as the nearest
  directory above `<dir>` holding `project.yml`, never over the network, as porelo-project's check clones them there.
- **`stories cut` links each story on the host and under the owner `project.yml` names** (`Project.web`), which for
  a project on github.com under porelo-ai is the build tools' fixed link.
- **Every line said has a repository's control, format and line separator characters escaped** as `\uXXXX`; that is
  the one place the planner's answer may differ from the build tools'.
- **The texts that name `porelo-release`** - three refusal texts and the stories index's heading - stay until the
  repository whose check reads them runs the planner's command.
- `AGENTS.md`'s rules on the stories index and the hand-over's issue checks, `doc/index.md`'s *The work tools*,
  `planner/app/README.md`, and a changelog entry.

Tests:

- Unit, `WorkCommandsTest`, over a fixture the build tools indexed once (`src/test/resources/work`: the project
  `porelo-sample` and the repository `porelo-alpha` beside it): `issues check` on an index the heads refuse says the
  fault and the count and exits 1; on a directory it cannot read exits 2; `issues index` writes the build tools'
  index; `stories check` refuses a story whose issue the sibling's index lacks and passes the fixture; `stories index`
  writes the build tools' story texts and index; `stories cut` writes their notes and links on the project's own host
  and owner; anything else is a usage with exit 2; a control character in a file name reaches no terminal; and `main`
  with `issues check`, in a JVM of its own, answers, exits 0 by itself and writes nothing under its home or runtime
  directory.
- Integration, test scope only while the build tools still carry the commands: `WorkCommandsMatchBuildToolsTest`
  runs `porelo-release` (a test dependency) and the planner's command line on copies of the fixture in ten cases -
  passes, refusals, a stale index, a stale story, an unreadable directory, the cut - and asserts the same standard
  output, standard error, exit code and files.
- Native, `NativeStartIT`: the five commands run by the native executable on a stale fixture answer as the JVM does,
  with the same files written, since the commands read schemas, YAML and JSON where only the image can lack metadata.

## Acceptance

- The unit and integration tests above, each seen red under a deliberate break of `WorkCommands` or
  `PlannerApplication`; the full build green, the native build and its `NativeStartIT` among it.
- `porelo-planner issues check issues` exits 0 on this repository, as `porelo-release issues check issues` does.
