# PL59 — The Work Tools Run From The Planner

```json
{
  "$schema": "issue-1.json",
  "id": "PL59",
  "title": "The Work Tools Run From The Planner",
  "state": "Estimated",
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

What stands today, read in the code:

- The work format lives in `planner/store/git`, package `org.fuin.porelo.planner.store.git.stories`: `IssuesIndex`
  (`write`, `check`), `StoriesIndex` (`write`, `check` over an `IssueSource`, `LocalIssues` reading the checkouts
  beside the project's own), `IssueLinks`, `IssueHead`, `IssueIndexes`, `Workflow`, `Project`, `Story`,
  `StageRecords`, and the schemas `issue-1.json` and the story schema. `GitStore` and `StoreReader` call it.
  `stories/api/PublicReadersTest` holds the readers a program outside the package uses.
- The planner ships one native executable, the server, inside a `.deb` and an `.rpm` that the Build publishes to the
  snapshots or releases channel of the project's Artifactory; `PlannerApplication.main` starts Spring at once, with
  `--mock` as its only argument of its own. Nothing of this repository is published to Maven Central.
- The build tools' commands this replaces: `issues index|check <dir>` and `stories index|check <dir>` print `OK ...`
  and exit 0, print each fault and exit 1, or exit 2 when something cannot be read or written; `stories cut
  <version> <dir> --after <date>` writes release notes from the stories tested after a date. porelo-project's
  `stories-check.sh` runs `stories check`; agents run `issues index` and `issues check` before a push.
- Three refusal texts and the stories index's own heading name `porelo-release` as the command to run.

The steps:

1. **The command line in the executable.** `PlannerApplication.main` hands its arguments to a small `WorkCommands`
   in `app` first when the first one is `issues` or `stories`, and starts Spring only otherwise, so a check starts no
   server, opens no port and reads no planner configuration. `issues index <dir>`, `issues check <dir>`, `stories
   index <dir>`, `stories check <dir>` and `stories cut <version> <dir> --after <date>` call the Git store's own
   `IssuesIndex`, `StoriesIndex` and their readers, with the build tools' arguments, output lines and exit codes (0,
   1, 2), and a usage line with exit 2 for anything else. `issues convert` is not carried over. The work format stays
   in `planner/store/git`, one copy.
2. **The bare executable published.** The Build publishes the executable on its own, with its sha256 beside it, to
   the same channel and under the same version as the packages, so a workflow downloads it with `curl -L` and checks
   the digest before it runs it. A release never replaces a published file, as for the packages.
3. **The texts that name the command** change to the planner's command only together with the switch of the
   repository whose check reads them: porelo-project's stories index heading is checked by its own `stories check`,
   so the heading changes once porelo-project runs the planner's check, never before.
4. `AGENTS.md`'s rule on the stories index, `doc/` on the commands, `planner/app/README.md`, and a changelog entry.

Tests, red first:

- Unit, in `app`: `WorkCommandsTest` runs `issues check` on a fixture tree whose index the heads refuse and asserts
  the fault lines and exit 1; on a tree it cannot read, exit 2; `issues index` on a fixture writes the same index the
  build tools write (fixture compared with the build tools' output once, kept as a file). The harm: a repository's
  check that passes what the build tools refused.
- Unit: `stories check` over a project fixture with sibling checkouts gives the same faults and exit code; `stories
  cut` writes the same release notes.
- Unit: `main` with `issues check` starts no Spring context and binds no port.
- Integration, test scope only while the build tools still carry the commands: the fixture run by both
  `porelo-release` (already a test dependency) and the planner's command line, outputs and exit codes equal.
- The native start test runs `issues check` on a fixture through the native executable, since the commands read
  schemas and YAML through reflection that only the image can miss.

## Acceptance

- The unit tests above, each seen red first; the full build green, the native build among it.
- The Build of a push to main publishes the bare executable and its sha256; downloaded with `curl -L`, its digest
  matches, and `porelo-planner issues check issues` exits 0 on this repository.
- This repository's own hand-over checks `issues index` and `issues check` run with the planner's executable.

## To be checked

- Whether `stories cut` builds its links from `project.yml`'s host and owner, as the planner's `Project` reads
  them, instead of the fixed owner the build tools use.
- The executable's published name and path on the channel, agreed in the channel with the build tools' agent, since
  every repository's workflow downloads it.
- Whether the commands' reflection on the schemas and YAML needs more reachability metadata in the image.
