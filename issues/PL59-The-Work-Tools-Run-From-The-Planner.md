# PL59 — The Work Tools Run From The Planner

```json
{
  "$schema": "issue-1.json",
  "id": "PL59",
  "title": "The Work Tools Run From The Planner",
  "state": "Analyzed",
  "size": "L",
  "priority": "High",
  "rank": 1,
  "blockedBy": [],
  "covers": "A repository's build indexes and checks its issues and stories with a command line of the planner, so no repository needs the build tools for its own work",
  "openQuestions": 2
}
```

**Size:** L - the repository's first module published to Maven Central, with a contract other repositories build and
run against, and the package that holds the work format moved out of the Git store.

**What must be true.** Every repository builds its issue and story indexes and checks them with a command line the
planner publishes, built from the Git store's own code, with the same results and exit codes as the build tools'
`issues` and `stories` commands give today; a repository changes only which artifact its build runs.

## Plan

What stands today, read in the code:

- The work format lives in `planner/store/git`, package `org.fuin.porelo.planner.store.git.stories`: `IssuesIndex`
  (`write`, `check`), `StoriesIndex` (`write`, `check` over an `IssueSource`, `LocalIssues` reading the checkouts
  beside the project's own), `IssueLinks`, `IssueHead`, `IssueIndexes`, `Workflow`, `Project`, `Story`,
  `StageRecords`, and the schemas `issue-1.json` and the story schema. `GitStore` and `StoreReader` call it.
  `stories/api/PublicReadersTest` holds the readers a program outside the package uses.
- Nothing of this repository is published to Maven Central yet; the Build publishes only the `.deb` and `.rpm`.
- The build tools' commands this replaces: `issues index|check <dir>` and `stories index|check <dir>` print `OK ...`
  and exit 0, print each fault and exit 1, or exit 2 when something cannot be read or written; `stories cut
  <version> <dir> --after <date>` writes release notes from the stories tested after a date. porelo-project's
  `stories-check.sh` runs `stories check`; agents run `issues index` and `issues check` before a push.
- Three refusal texts and the stories index's own heading name `porelo-release` as the command to run.

The steps:

1. **A module of its own for the work format.** `planner/work` (artifact `porelo-planner-work`), a plain Java module
   with Jackson and no Spring, takes the package `stories` from `store/git` by a move, renamed to
   `org.fuin.porelo.planner.work`, with its tests, fixtures, schemas and the native test; `store/git` depends on it.
   One copy, never two. Its public readers (`Workflow.read`, `IssueHead`, `IssueIndexes.read`, `IssueLinks`,
   `Project.read`, `Story.read`) stay public, since a check outside the planner reads issue rows, links, story files
   and the workflow through them; `PublicReadersTest` guards that.
2. **The command line.** `org.fuin.porelo.planner.work.Main` in the same module: `issues index <dir>`, `issues check
   <dir>`, `stories index <dir>`, `stories check <dir>`, and `stories cut` if the operator keeps it, each with the
   build tools' arguments, output lines and exit codes (0, 1, 2), and a usage line with exit 2 for anything else.
   Run as `exec:exec` with the module as executable dependency, the way a repository runs the build tools today;
   this repository's root pom gets that execution as `work`.
3. **Published to Maven Central.** The root declares `flatten-maven-plugin`; only `porelo-planner-work` opts in.
   The Build gets the deploy job and the deploy dry run read by `check-deploy`, as the build tools' Build does, with
   the Sonatype credentials from the organization's secrets.
4. **The texts that name the command** change to the planner's command only together with the switch of the
   repository whose check reads them: porelo-project's stories index heading is checked by its own `stories check`,
   so the heading changes once porelo-project runs the planner's check, never before.
5. `AGENTS.md`'s rule on the stories index and `planner/store/git/README.md` name the new module; a `README.md` for
   `planner/work`; a changelog entry.

Tests, red first:

- Unit, in `planner/work`: `MainTest` runs `issues check` on a fixture tree whose index the heads refuse and
  asserts the fault lines and exit 1; on a tree it cannot read, exit 2; `issues index` on a fixture writes the same
  index the build tools write (fixture compared with the build tools' output once, kept as a file).
- Unit: `stories check` over a project fixture with sibling checkouts gives the same faults and exit code.
- Integration, test scope only while the build tools still carry the commands: the fixture run by both
  `porelo-release` (already a test dependency of `store/git`) and the planner's `Main`, outputs and exit codes
  equal.
- The existing `store/git` tests stay green after the move, unchanged but for imports.

## Acceptance

- The unit tests above, each seen red first; the full build green, the native build among it.
- The deploy dry run of the Build read green by `check-deploy`; a snapshot of `porelo-planner-work` published and
  visible in the index at its exact version.
- This repository runs `issues index`, `issues check` from its own published snapshot, and the three commands of
  the hand-over exit 0 with it.
- `porelo-release` is no longer needed by this repository's work commands; it stays only for the shared checks.

## Open questions

- Does `stories cut` (release notes from the stories tested after a date) move into the planner's command line, or
  is it dropped? It is proposed to move with `stories check`.
- Is the one-time `issues convert` (a head written from prose lines and the index row) dropped rather than moved?
  It is proposed to drop it.

## To be checked

- The coordinate `org.fuin.porelo:porelo-planner-work` and the command names, agreed in the channel with the build
  tools' agent, since other repositories build and run against them.
- Before the first publish, the operator verifies that this repository may read every organization secret the
  deploy needs, the publication credential among them.
- Whether `stories cut` builds its links from `project.yml`'s host and owner, as the planner's `Project` reads
  them, instead of the fixed owner the build tools use.
- Whether the native test of the moved package still needs its own reachability metadata in the new module.
