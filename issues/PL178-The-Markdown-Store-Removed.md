# PL178 — The Markdown Store Removed

```json
{
  "$schema": "issue-1.json",
  "id": "PL178",
  "title": "The Markdown Store Removed",
  "state": "Analyzed",
  "size": "L",
  "priority": "High",
  "rank": 2,
  "blockedBy": ["PL168", "PL179"],
  "covers": "No stories or issues stay in a project's repositories: the planner's repository-bound Markdown store, its work format commands, stage records and fixtures go once the cut-over's copy is done, the local directory store taking its place"
}
```

**Why this size.** L, not M: about half of `store/git`'s 17,400 lines of main code go, with their tests and
fixtures, the executable's commands that other repositories' builds call, the bash story tests and the probe, and
the documents; not XL, since what stays - reading the project's own repository and lending the login - is already
its own code, and the GitHub store, the mock and `core` keep their shape.

**What must be true.** No repository holds `issues/` or `stats/` for the planner to read, and nothing of the
planner serves them; a project without a forge's tracker uses the local directory store
([PL179](README.md#pl179)).

## Plan

- **Built after the cut-over's copy**: [PL168](README.md#pl168)'s carry-over reads the Markdown store once, so it
  and the reading it needs (`CheckoutProject`, `CarryOverPlan`, `ReferenceRewrite`, the `stories` reading) stay
  until the copy is done, and go in this issue's last step together with the `carry-over` command.
- **What stays in `store/git`**, renamed in its README to the project's own repository: the clone of the own
  repository and the reading of `project.yml`, the workflow and the work rules (`GitProjects`, `ProjectFile`,
  `Checkouts`, `CloneUrl`, `GitRunner`, `GitCall`, `ProjectLock`, `FileProjectLock`, `CacheDirectory`), the lent
  login (`LentLogin`, `ForgeTokens`, `PoreloExecutable`), `PrivateFiles`, `LogText`, `ResultText`, and `pushes`
  (`check-pushes` reads GitHub's repository activity, no issue).
- **What goes**: the Markdown board and its writes (`GitStore`, `StoreReader`, `PartReader`, `WorkReader`,
  `StoryFile`, `IdeaFile`, `HeadEdit`, `SaveFiles`, `CommitFiles`, `LocalSaves`, `LocalRefs`, `GitLocalRefs`,
  `GitRemote`'s board reading), the work format `stories/*` (`IssueIndex`, `IssuesIndex`, `StoriesIndex`,
  `IssueLinks`, `IssueHead`, `StoryHead`, the schemas, `StageCommand`, `StageRecords`, `StageTokens`), and the
  executable's commands `issues index|check`, `stories ...` and `stage ...`. `check-commits` keeps the message
  rules and drops the stage-record rule. `GitProjects.open` refuses a project whose `project.yml` names no tracker,
  saying a tracker is needed, `local` among them.
- **Fixtures**: porelo-sample and the alpha work fixture move to the local directory store
  ([PL179](README.md#pl179)), whose files a test writes, or to the mock board; the app's tests that open a Markdown project (36 test classes name a fixture)
  open one of those; the Git store's board tests go with the code. `NativeStartIT` starts against the mock and a
  faked GitHub. `tool/story-tests/` and `tool/planner-probe.sh` read Markdown commits: they go, and the joint run's
  scripts against the test organization take their place.
- **Documents**: the Markdown chapters of `doc/index.md` and the decisions that rest on the store go; `AGENTS.md`'s
  lines on the work format and on indexing this repository's issues with the planner's command change through the
  channel, since the shared rules on `issues/` change with them.
- **Others' builds**: every repository's CI calls the executable's `issues check` and the shared skills describe
  `issues/`; those are their owners' and the coordinator's changes, made before this issue's commands go, so no
  Build turns red. This issue names the commands that go and the version from which they are gone.
- Red first: a `GitProjects` test that a `project.yml` without a tracker is refused with its reason; a
  `WorkCommands` test that `issues`, `stories` and `stage` answer as unknown commands; the moved fixtures' tests
  stay green over the fakes.

## Acceptance

- The native planner opens the test organization's GitHub project and a local directory project, and refuses a
  project without a tracker; the
  executable no longer offers `issues`, `stories` or `stage`; `store/git` holds no Markdown reading.

## To be checked

- The order with the other repositories' removal of their `issues check` steps, with the coordinator.
