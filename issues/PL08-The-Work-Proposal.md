# PL08 — The Work Proposal

```json
{
  "$schema": "issue-1.json",
  "id": "PL08",
  "title": "The Work Proposal",
  "state": "Implementing",
  "size": "M",
  "priority": "Medium",
  "rank": 7,
  "blockedBy": ["PL07"],
  "covers": "The work proposal: main agent per repository, each sub-issue a task of its own, the operator's choices, comment and reading limit, and the prompts the window starts each task with"
}
```

**Why this size.** the proposal per repository from the work rules and the sub-issues, the operator's choices, and the
prompts the window starts each task with.

**What must be true.** For a story about to enter a working state, the planner proposes what to start: per
repository (or for the story) one main agent with its model and order, and each sub-issue as a task of its own with
its role, agent, model and brief. The operator keeps, changes or drops each sub-issue's task, adds a comment and sets
the reading limit for the story or single issues (default 2). The planner then answers, per task, everything the
window passes to Porelo's `Start`: repository, agent, model, mode and the prompt. A dropped sub-issue's brief goes into
the main agent's prompt as its own work. The planner starts nothing itself.

## Plan

- **Contract first:** extend `planner/app/src/main/resources/openapi/planner.yaml` with these POSTs, bodies and
  problems before implementation; the contract points are settled under *Decided*.
- `POST /projects/{project}/stories/{story}/proposal`, body `{to}`, answers
  `{modes, repositories, coordinator, comment, readingRounds, issueReadingRounds, revision}` directly, as
  `StoryWorkProposal` reads it. Each repository is `{repository, issues: [id], main, subAgents}`; `main` is
  `{name, role: MAIN, agent, model, brief}` (`PlanWorker`), and each sub-agent is
  `{id, issue, name, kind, agent, model, workingState, brief, choice}` (`PlanAssignment`); `id` is stable within
  its repository and `kind` carries the role. Defaults are `comment: ""`, `readingRounds: 2`,
  `issueReadingRounds: []`, and `choice: KEEP` for known roles; `OTHER` is always `DROP`. The fake's coordinator
  proposal is `START` or `TELL`, depending on an existing active coordinator.
- `POST /projects/{project}/stories/{story}/work` takes `StoryWorkRequest.toMap()` unchanged:
  `{project, story, to, mode, revision, repositories?, comment?, readingRounds?, issueReadingRounds?}`; the nested
  repositories have the proposal's shape with the chosen main agent and model and each sub-agent's choice. Omitted
  repositories and limits take the proposal's; an omitted comment is empty; overrides are `[{issue, rounds}]`. It
  answers `{revision, tasks: [{repository, issues, role, agent, model, mode, prompt}]}`: one entry per task the
  window starts with its `PlanningStartTask`, which calls Porelo's `Start`. It starts nothing and moves nothing; the
  window reports the started tasks back and running work shows in `getStoryWork`, both in the run of the work. The
  screen's `StoryWorkStarted`, its fake and its HTTP calls follow this answer in the screen package's own issue.
- **Plain core:** add `WorkProposal`, `WorkOrder` and a Spring-free `WorkRefusal` in
  `planner/core/src/main/java/org/fuin/porelo/planner/core/`. Compute from one immutable `Board` and its `Revision`,
  `Story.issues` and matching `Issue.parent`/`text`, using sound rules from `WorkRules.check`.
  Require the target rule and current story state in its `from`. Group by `Issue.repository` when `per` is
  `REPOSITORY`; when `STORY`, one main task in the project's own repository (the first of its repositories, which
  the store names), naming every issue of the story. Propose one main per group, using the first agent/model entry
  of the ordered `models`, the rule's modes (first default), and its `order`. Propose one `BUILDER` per sub-issue, its
  agent and model the rule's first by default and changeable by `CHANGE`, its brief the sub-issue's title and
  criteria; the reader belongs to the run's reading round, not to this start. Expand
  `{story}`, `{issues}` and `{tasks}` from this board and the final task list. Recompute at acceptance; `KEEP`
  retains the participant, `CHANGE` replaces agent/model, and `DROP` creates no task. Compute final tasks with
  repository, issue identity, role, agent, model, selected mode and starting prompt: main prompt includes the
  expanded order, relevant issues, comment and every dropped sub-issue's brief as the main agent's own work;
  each retained sub-issue's prompt includes its order, brief and comment. `done` comes from the rule; `then` and
  `blocked` describe later work, not work started by acceptance.
- **Refusals:** `NoSuchStory(story)` when absent; on acceptance check `StaleRevision(revision)` (the current
  revision) before `WorkRunning(story, tasks)` (recorded task names) when recorded work is active.
  `WorkNotAllowed(from, to, reason)` covers a missing/unsound target rule, disallowed `from` or mode, missing,
  duplicate or unproposed repositories/assignments, invalid main role or empty main agent/model, changed helper
  `id/issue/name/kind/workingState/brief` (reason `A helper identity or brief was changed.`), unknown choice or
  non-dropped `OTHER`, blank comment for any `CHANGE`/`DROP`, and non-integer or less-than-one overall/issue limits.
  `InvalidSettings(detail)` covers invalid project input in the fake; use it for malformed work bodies and
  path/body disagreement rather than the current `NotACloneUrl` handler. Preserve inherited
  `InvalidName(project?)` for invalid path IDs, `NoSuchProject(project)` for an unknown project,
  `WorkflowMissing(repository)`, `CredentialRefused(repository)`, `Unreadable(repository)` and
  `Unreachable(repository)` for store failures, and the empty 401 for missing/wrong bearer token. Map core
  refusals through app `Refusal`/`Problems` to `application/problem+json`, type `urn:porelo-planner:<Name>` and
  typed `parameters`, allowing `tasks` as a string array.
- **Boundary and wiring:** acceptance computes an order only: it starts nothing and changes neither story state
  nor store revision. Task events, the durable running record, stop and readings belong to the run. A narrow
  read-only running-work input permits fake records in core tests; production knowledge is absent today:
  store `Work` holds rules, `Story` has no run, and `BoardController` answers `work: null`. Do not infer running
  work from state or invent runtime records. Wire the calls through
  `BoardController.java`, add request/answer records alongside `Answers.java`, and extend `Problems.java` and
  `Refusal.java`; keep Git behind `PlanningStore`. Client wiring is later: `PlanningSource.proposeStoryWork` is
  currently unsupported by `HttpPlanningSource`; start enters through `PlanningStartTask` in
  `hooks.dart`, not a `PlanningSource.startStoryWork` method.
- **Red first:** core unit `WorkProposalTest` over fake board/run inputs: both `per` values, two repositories,
  sub-issues and ordered model defaults prevent tasks in the wrong repository or with the wrong agent;
  bad target/`from`, stale revision and running records prevent unauthorized or duplicate work. Core unit
  `WorkOrderTest`: every choice/refusal above prevents unreviewed replacement work, altered identities and invalid
  limits; prompt assertions on included work prevent a dropped brief from being lost. Assert unchanged board and
  revision to prevent acceptance from moving a story. App HTTP integration `StoryWorkHttpTest` posts the exact
  window bodies and checks responses/refusal parameters, including array-valued `tasks` and malformed JSON,
  preventing a client decision from being misread or a refused call from becoming a success. Extend unit
  `ApiMatchesDescriptionTest` for both operations and all nested request/answer schemas, preventing HTTP contract
  drift. Observe executable failures before implementation.

### The plan reading, taken in

- **Nothing of a prompt comes from the body.** Every task's prompt, issues, agent, model and brief come from the
  proposal recomputed on the current board; the body only chooses. A changed `main.name`, `main.brief`, a
  repository's `issues`, or a `KEEP` sub-agent's `agent` or `model` is refused as `WorkNotAllowed`, like a changed
  helper identity.
- **Only what the rule offers.** `main.agent`/`main.model` and a `CHANGE`'s `agent`/`model` must be one entry of
  the rule's `models` (agent key and its model), and `mode` one of its `modes`; anything else, blank included, is
  `WorkNotAllowed`. `WorkRule` refuses a blank mode, agent or model, so `WorkRules.check` reports such a rule as a
  fault and no work is proposed under it.
- **Placeholders per task, in one pass.** For `per: REPOSITORY`, a main task's `{issues}` and `{tasks}` name only
  that repository's issues and retained sub-issue tasks; a sub-issue task's `{issues}` names only its sub-issue and
  its `{tasks}` is empty. `{story}`, `{issues}` and `{tasks}` are replaced in a single scan of the rule's `order`,
  so text from the repositories is never read for placeholders again.
- **Repository text is data, delimited.** A brief holds an issue's id, its title and its *What must be true*
  paragraph, its title alone when there is none; never the whole issue text or the story's description. Each piece
  of repository text enters the prompt inside a fence longer than any run of backticks in it, under a line saying
  it is the issue's text, so it cannot pose as the rule's order or the operator's comment, which stand outside
  any fence.
- **The project's repositories are on the board.** `Board` carries the project's repositories, its own first, and
  refuses an issue of a repository not among them; the Git store and the mock mode fill them, and the old
  constructor keeps its issues' repositories in order for the tests. `per: STORY` uses the first; `per: REPOSITORY`
  on a story without issues is `WorkNotAllowed`.
- **Only the story's open issues.** A story naming an issue the board lacks is `WorkNotAllowed`. A sub-issue
  belongs to a task only when its `parent` is an issue of the story, it is in the parent's repository, and its
  state is not closed; an issue of the story that is a sub-issue of another of its issues is named once, under
  its parent. `issueReadingRounds` may name only the proposal's issues, each once.
- **Bounds.** A work body is at most 64 KiB, a comment at most 4000 characters, `readingRounds` and each issue's
  rounds at most 10, a brief at most 4000 characters, and one task's prompt at most 64 KiB; past a bound the call
  is refused, never shortened.
- **Refusals echo only checked words.** A body that cannot be read answers `InvalidSettings` with a fixed detail,
  never the parser's text; a path variable enters `parameters` only once `Ids` accepted it, else `InvalidName`
  without it; `PUT /projects/{project}` keeps `NotACloneUrl`. `to` enters a refusal only once it is a state of the
  workflow; otherwise the refusal is `InvalidSettings` with a fixed detail.

## Acceptance

- The red-first test seen red, then green.

## Decided

- The start call answers the tasks to start with their prompts, starts nothing and moves nothing; one `BUILDER` per
  sub-issue with the rule's first agent and model by default; `per: STORY` is one main task in the project's own
  repository naming every issue. `WorkRunning` needs the run's record, which does not exist yet: until the run
  records work, a story is never refused as running, and the run adds that check.
