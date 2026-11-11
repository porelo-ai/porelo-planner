# PL09 — The Run From Building To Review

```json
{
  "$schema": "issue-1.json",
  "id": "PL09",
  "title": "The Run From Building To Review",
  "state": "Review",
  "size": "L",
  "priority": "Medium",
  "rank": 8,
  "blockedBy": ["PL08"],
  "covers": "The run from building through Reading and Reworking to Review: the reading limit, Needs you and its three choices, findings, a local record; task changes told by the window"
}
```

**Why this size.** the flow build -> Reading -> Reworking or Review, the reading limit, the operator's choice when it is
reached, the findings and a record that survives a restart.

**What must be true.** When a task of a story's work ends, the planner judges its end once: success leads to the
next working state (Reading) or to the done state; a reading with blocking findings leads to Reworking while the issue
has readings left, notes alone go with the issue into Review. On the last allowed reading with blocking findings the
issue stays in Reading, nobody at work, under *Needs you* with its findings, and the operator chooses Review anyway,
one more reading, or Rework with their own words. A task blocked or failed needs the operator and never counts as a
finding. Counts, limits and findings survive a restart of the planner.

## Plan

- **The window's report, as agreed:** `taskEvent(project, task, event, {offer, container, ended?})`, where `task` is
  Porelo's task name, `container` the container's name, `offer` the identity the work answer gave, kept by the window,
  and `ended` Porelo's record of the agent's end passed unmapped: `{at, finished, why, answer, unpushed}`. Porelo
  says no "blocked": an unfinished run is FAILED, a finished one with commits left unpushed needs the operator as
  UNPUSHED, and only a finished, fully pushed end is DONE. Where the points below name `outcome` or BLOCKED, this
  holds instead.
- **Contract first:** extend `planner/app/src/main/resources/openapi/planner.yaml`; preserve screen fields and
  envelopes. `P` means `/projects/{project}`. Only the window calls Porelo's `Start` and reports task changes.
- **Preparing work:** retain `POST P/stories/{story}/proposal`, `{to}` ->
  `{modes,repositories,coordinator,comment,readingRounds,issueReadingRounds,revision}`, and `POST
  P/stories/{story}/work`, `{project,story,to,mode,revision,repositories?,comment?,readingRounds?,issueReadingRounds?}`
  -> `{revision,tasks}`. Keep `WorkProposal`/`WorkOrder` validation and repository choices. Each task is
  `{repository,issues,role,agent,model,mode,prompt}`, with a new opaque UUID `offer`. Persist these offers and chosen
  limits before answering, without marking anything running or moving states. Repeated identical decisions reuse
  unstarted offers; replacements invalidate superseded offers. Feed active records to `WorkProposal.RunningWork`.
- **Reports:** `POST P/tasks/{task}/events` takes `{offer,event,container,ended?}`, with
  `event: STARTED|ENDED|STOPPED`; only ENDED requires `ended: {at,outcome: DONE|BLOCKED|FAILED,why,answer}`.
  Answer `{revision,work,tasks}` with next Start arguments and offers. The saved
  offer binds project, story, repository, owned issues, role, working rule and run. STARTED binds the actual Porelo
  task name and container id once; later reports must match both. Scope never comes from a report.
  Reject unknown, superseded or foreign offers, rebinding, an end before a start and conflicting terminal reports.
  Deduplicate by offer, event and checked payload: repeats replay the disposition after restart, without another
  judgment, count or save; return only still-valid next offers, with unchanged identities. The window starts each once.
- **Reads and stop:** `GET P/stories/{story}/work` has no body and answers `{work}` (`null` without a run).
  Project story-list, story-detail, story-issues and issue-detail GETs keep `{revision,stories}`, `{revision,story}`,
  `{revision,issues}` and `{revision,issue}`. Decorate them and the existing board SSE with the same snapshot:
  `PlanWork` is `{tasks,coordinator,since,atWork,repositories,comment,readingRounds,issueReadingRounds,issues}`;
  each task has `{task,containerId,repository,issues,state,done,mode,agent,model,role,since,atWork,ended}`.
  Issue views carry `rounds,roundsMost,atWork,needsYou,readingStopped,findings`; work issues add `issue,reason`
  (`NONE|ROUND_LIMIT|BLOCKED|FAILED|STOPPED|INVALID_FINDINGS|SAVE_REFUSED`). Findings are
  `{number,severity: BLOCKING|NOTE,file?,line?,text}`. `POST
  P/stories/{story}/work/stop`, empty body -> `{work}`, records cancellation, invalidates unstarted offers and
  suppresses follow-ups. The window stops active tasks and reports STOPPED; only those reports clear their
  `atWork`. Stop preserves planning states, counts and findings and is repeatable.
- **Plain core:** add `WorkEnds`, `ReadingCycle`, `NextStep` and `WorkRun` under
  `planner/core/src/main/java/org/fuin/porelo/planner/core/`. `WorkEnds` judges one accepted terminal report:
  BLOCKED, FAILED and STOPPED need the operator, never findings or continuation. Parse only a successful
  READER's answer: exactly one final `Findings:` block, through end of message, empty for no findings; otherwise
  consecutive numbered lines `<n>. <blocking|note> | <file or -> | <line or -> | <text>`. Split only the first three
  pipes; preserve text, map `-` to absence. Missing, duplicate or malformed blocks need the operator without advancing
  or counting a reading; keep the raw answer. Each reader owns one issue; findings cannot name another issue.
- **Rounds and decisions:** `ReadingCycle` counts a successfully parsed reading once per issue, not an offer or
  STARTED. Defaults and per-issue overrides come from the accepted order (default 2, initial allowance 1..10).
  Notes or no findings reach Review, including on the last reading. Blocking findings with allowance left lead to
  Reworking; at `rounds == roundsMost`, stay in Reading with `atWork:false,needsYou:true,readingStopped:true`,
  `reason:ROUND_LIMIT`, findings retained and no next task. `POST P/issues/{issue}/reading` takes
  `{story,round,choice,comment,revision}` and answers `{issue,work,revision,tasks}`. Check revision, membership,
  stopped status and exact round. `REVIEW_ANYWAY` saves Review, retains findings and offers nothing;
  `ONE_MORE_ROUND` grants `max(roundsMost,rounds+1)` and offers a reader; `REWORK` needs nonblank operator words,
  grants that allowance and offers rework using those words. Neither counts a reading before its successful end.
- **Next work:** `NextStep` uses sound `WorkRules.check` rules: success follows `then`, else `done`; findings follow
  `blocked` only within allowance. Building/Reworking success offers READER; rework includes findings/operator words.
  Expand the target order through `WorkOrder`'s fenced briefs, story, task identities and comment; append Findings
  grammar for readers. Check target `from` on state changes, plus agent/model and mode. Kept BUILDERs own sub-issues; MAIN owns
  remaining work, including dropped briefs, never a kept builder's issue. Use `PlanningStore.storyState` for aggregate
  state, removing agreeing overrides; write the story head for stories without issues.
- **Saving:** `WorkRun` uses record/store interfaces. STARTED and transitions compute
  `Writes(issues,stories,unstated)` against the board revision for `PlanningStore.save`, preserving parent/closed
  invariants. `Moves.check` refuses working states; use sound work rules here. `GitStore.save` owns commits, pushes
  and indexes. Before saving, persist event/judgment and pending `{baseRevision,sourceStates,targetStates,writes,offers}`;
  release offers only after save and final record replacement. StaleRevision/Busy retain judgment, return refusal,
  expose Needs you, release nothing. PartlySaved records pushed/failed repositories, refreshes/publishes actual states,
  never retries or rolls back. After restart, saved revision evidence proves finalization; matching states alone are
  ambiguous and stop without replay.
- **Local writer:** add app `WorkRecord` and wire it in `StoreConfiguration`. Versioned JSON at
  `$XDG_STATE_HOME/porelo-planner/<project>/work.json` (an unset XDG_STATE_HOME uses the XDG default state directory) has
  `{version,project,generation,runs}`; each run contains story/identity, decisions/rules/limits, offers/ownership,
  task/container bindings, lifecycle/deduplication results, issue rounds/findings, raw readings, cancellation and pending saves.
  One project writer holds a mutex and stable sibling `work.lock` across record/save/finalization; acquisition is
  bounded by 30 s. Flush an owner-only temporary sibling, atomically replace and sync its directory before publishing;
  refuse unsupported replacement or unreadable records. A lost record leaves store states intact: omit unknown
  counts/findings, return no run, and never infer zero rounds, running tasks or permission to continue.
- **Bounds and refusals:** strict JSON rejects duplicate/unknown fields and trailing input. Bodies are at most
  64 KiB, reports 128 KiB; prompts and raw answers 64 KiB UTF-8 each; comments, reasons and finding text 4,000
  Unicode code points; at most 100 findings, relative file labels 512 characters, lines 1..2,147,483,647, no line
  without a file; file labels are display-only. Reuse `Ids`; task/container labels match
  `[A-Za-z0-9][A-Za-z0-9_.-]{0,127}`, offers are UUIDs, timestamps RFC 3339, other scalar labels at most 128
  characters, round counters 0..2,147,483,647. Preflight pending/final records and JSON answers
  against 16 MiB before saving; never truncate history or release work on overflow/disk failure.
  Problems retain `urn:porelo-planner:<name>` and typed parameters: 400 `InvalidName(project?)`,
  `InvalidSettings(detail)`, `WorkNotAllowed(from,to,reason)`; 404 `NoSuchProject(project)`, `NoSuchStory(story)`,
  `NoSuchIssue(issue)`, new `TaskNotAnswered(task,offer)`; 409 `StaleRevision(revision)`, `WorkRunning(story,tasks[])`,
  `ReadingNotStopped(issue)`, `ReadingRoundChanged(issue,rounds)`, new `TaskEventConflict(task,event,reason)`;
  422 `MoveNotAllowed(item,from,to,reason)` for unsavable/invalid state writes; 501 `NotOffered(project)`;
  502 `WorkflowMissing(repository)`, `CredentialRefused(repository)`, `Unreadable(repository)`,
  `PartlySaved(pushed,failed)` (pushed comma-joined); 503 `Busy(repository)`, new
  `WorkRecordUnavailable(project,reason)` for record IO/capacity; 504 `Unreachable(repository)`.
  Invalid input gets fixed words, never echoed raw messages or parser errors; task outcomes needing attention are
  accepted reports, not HTTP refusals.
- **Wiring and red first:** extend app `BoardController`, `WorkRequests`, `WorkAnswers`, `Answers`, `Problems`,
  `Refusal`, core `WorkRefusal`, and `Changes`/`ChangesRegistry`/`EventsController`: publish record generations at
  unchanged store revisions; register SSE/record native bindings. First core unit tests over fakes: `WorkEndsTest`
  prevents BLOCKED text becoming findings or malformed readings reaching Review (also pipes/empty blocks);
  `ReadingCycleTest` prevents double counts and work past the limit, covering three choices/last-round success;
  `NextStepTest` prevents MAIN completing another owner's issue; `WorkRunTest` prevents forged cross-story reports,
  duplicate follow-ups and continuation after save refusals. App `WorkRecordTest` disk integration prevents lost
  counts/findings, reset allowance or state corruption after restart/record loss, and competing writers losing updates.
  Extend `ApiMatchesDescriptionTest`; add `WorkRunHttpTest` for envelopes, refusals, bounds and same-revision SSE
  (harm: wrong starts or hidden Needs you); exercise native bindings in `NativeStartIT`. See each guard fail first;
  the complete cycle uses fakes, without Porelo or a VM.


### Taken from the plan reading

These hold over the points above where they differ.

- **Ownership.** A MAIN task owns its repository's issues except those of the builders kept beside it; a builder its
  one sub-issue. Every reader, and every rework that follows one reader's blocking findings or the REWORK choice,
  owns exactly that one issue, in `issue.repository()`, with the target rule's default agent and model. Before any
  write, every owned issue must still belong to the bound story and repository on the current board; only the bound
  story is stated or unstated; a parent goes on only once its participating sub-issues have.
- **Readings come only from the run.** A target whose rule names `blocked` is a reading state; the proposal and the
  work call refuse it as `WorkNotAllowed`. Blocking findings under a reading rule without `blocked` stop in Needs you.
- **Agent text is fenced.** Findings and the operator's words go into a rework prompt inside a fence longer than any
  backtick run in them, as `WorkOrder` fences an issue's brief. A finding's file label may not start with `/` or `\`
  nor hold a `..` segment.
- **A choice takes effect at once.** REVIEW_ANYWAY, ONE_MORE_ROUND and REWORK clear the stop when recorded, before
  any offer leaves, so a second choice on the same round is `ReadingNotStopped`; a grant past the largest count is
  refused.
- **A run halts on any refusal of its save.** Any `StoreException` from a save keeps the judgment, sets
  SAVE_REFUSED and Needs you, voids every unstarted offer of the run and gives no new one; ends and stops of tasks
  already started are still recorded. After a stop, a late `ENDED` is recorded and clears `atWork`, without a save,
  a count, findings or an offer; a reading choice on a stopped or halted run is refused. The work call is refused
  while the story's run is halted or a reading of it waits for a choice; a new run keeps the counts, allowances and
  findings of the one before.
- **A report is never refused for what follows it.** When the next step cannot be built - an unsound or missing
  rule, a prompt over its bound - the end is still recorded and saved and the issue stops in Needs you without an
  offer.
- **Moves and work do not race.** A window's move of an item a run owns while it is at work is refused as
  `WorkRunning`, checked under the record's lock.
- **A lost record never restarts counting.** A story standing in a working state without a run in the record
  refuses the proposal and the work call, and its issues read `needsYou`.
- **The record.** `$XDG_STATE_HOME` and `$HOME` must be absolute, without `..`; the directories are created owner-only,
  no part of the path may be a link, the temporary sibling is created new. The record is read and checked inside the
  lock, names its project's repository and branch, and is cleared when `PUT /projects/{project}` changes them; in
  mock mode it is kept in memory and reset with the mock. It keeps only each story's current run and each issue's
  latest reading answer; its size is checked before it is read, and a stop or a task's end always fits, since it
  replaces what it ends. `WorkRecordUnavailable` carries fixed words only; a refusal names a task, offer or event
  only once it is checked. The stop call takes an empty body.
- **Cut.** A story's work without an open issue is refused where a reading would follow. A save in flight is
  recorded as a single marker; found at start-up, it reads as SAVE_REFUSED. A repeated work call replaces the
  unstarted order instead of reusing it. One planner writes a project's record: an in-process lock and a file lock
  taken without waiting, which refuses a second planner.

### Taken from the code reading

- **Fixed:**
  - A replayed STARTED answers the follow-ups the first one made.
  - The issue's progress is read from the run at work on it, otherwise from the one with the most readings.
  - A reading choice resumes a parent deferred behind the child it settles.
  - A story move is refused while any of its issues is at work in another story's run.
  - A halted run takes a new order once the person has stopped it.
  - Requests of one planner wait for each other on the record's lock instead of refusing.
  - An older reading's answer is kept until the newer reading ends.
- **Declined:**
  - A new order over an issue that already had readings keeps that issue's allowance, as planned: a new run keeps
    the counts, allowances and findings of the one before.
  - Ordering again over a run whose task failed, or between a task's end and the next start, replaces its unstarted
    offers: that is the person's own choice, and the tasks at work are refused as before.

## Acceptance

- The red-first tests seen red, then green; a whole cycle run over fakes in seconds.

## To be checked

- `screen/lib/src/models.dart` makes `StoryWorkStarted.started` require `{work}` with `atWork:true` and tasks;
  `fake/story_work.dart` changes story/issue states and returns that running work from StartStoryWork. The settled
  work call instead returns `{revision,tasks}` and starts/moves nothing. The screen's own work must follow that
  answer, carry offers through Porelo starts and handle tasks returned from reports and reading decisions.
- The fake proposes BUILDER and READER together, carries readers as chosen start participants, and its reading
  decision sets `atWork` itself without answering a task to start. Readers instead belong to the run after building
  success; only STARTED reports establish activity. The fake's StopStoryWork likewise clears activity before any
  STOPPED report. Keep those differences in the screen work rather than reproducing them in the planner.
- The fake increments `rounds` on ONE_MORE_ROUND before a reader ends and accepts initial limits above 10; the
  implemented work contract bounds initial limits to 1..10. Verify the screen's round display against completed
  readings and retain operator-granted extensions beyond that initial bound.
- `client.dart` exposes `taskEvent(project,task,event)` as `Future<void>` with an enum alone: no offer, container,
  ended record or next-task answer. The HTTP source marks work, stop and reading calls unsupported. Verify the
  window adapter's actual Porelo ended payload and task/container names against the planned report and parser.
- The fake returns reading resolutions as `{issue,work,revision}` without tasks. Its Needs you list recognizes
  non-DONE task ends or `readingStopped`, whereas malformed DONE readings and save refusals also need attention
  without being round-limit stops. Check that the screen exposes these reasons and preserves their raw reports.
- `WorkOrder` allows story work without issues, but reading decisions and round-limit controls in the screen are
  issue-scoped. Check how that case exposes a stopped reading before extending either contract.
