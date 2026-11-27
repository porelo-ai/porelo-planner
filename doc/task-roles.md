# Task roles in the work rules

Every task the planner starts takes a role, and every rule in `stories/work.yml` names the role it offers. The planner
knows no role by its name: a rule names only a role its own file defines. A project a tracker keeps, whose default
branch has no `stories/work.yml`, takes the work rules the planner carries, which define their own roles the same
way. This page shows how to write a role. What each right allows is defined by Porelo, on its page on
[the parts of a start](https://porelo-ai.github.io/core/rights/#the-four-parts-of-a-start).

## What a role is

A role has a name, a way its task ends, and the parts of a start that Porelo takes. Porelo knows no role by its name:
the planner hands it only the parts.

- **`end`**: `push` pushes work, `findings` reports findings, `points` gives points at the place the task works at,
  `result` hands back a result.
- **`workspace`**: `own`, `foreign` (another task's work), `readOnly`, `allRepositories` or `none`.
- **`readLanded`**: whether the task also reads the forwarded work of the tasks whose build it reads; `false` when
  absent. Only for a role with `readLanded: true` does a start name the task whose build is read; a role at another
  task's work reads that work through its place.
- **`projectServices`**: whether the task has the project's services; when absent, `true`, except at `foreign`, where
  it is `false`.
- **`writeAs`**: the account its released words are written as at a pull request, `author` when absent, or `review`.
- **`rights`**: a list of Porelo's words. No role loads with an empty list.

`end`, `workspace` and `rights` are required. At another task's work, what a task finds stands at that place, in
threads there, so a role that reports findings there needs `openThreads`. A role that reports findings and does not
work at another task's work says them in its answer.

## The roles section

Roles stand under `roles:`, and each rule offers one role by `role:`; a role no rule offers still loads. A rule whose
role reads (end `findings` or `points`) names `blocked`, a working state whose own role does not read, and no `then`:
a clean reading moves its issues to `done`. A rule whose role does not read names no `blocked`, and the `then` of a
rule whose role pushes work leads to a rule whose role reads. A rule with a `result` names no `then` and no
`blocked`. A project lets its tester or screener read instead of its reviewer by naming that role in the reading
rule.

```yaml
roles:
  author: {end: push, workspace: own, rights: [push, reply]}
  reviewer: {end: points, workspace: foreign, writeAs: review, rights: [reply, openThreads]}
  tester: {end: findings, workspace: foreign, writeAs: review, rights: [openThreads, report]}
  screener: {end: points, workspace: foreign, writeAs: review, rights: [reply, openThreads, reject]}
work:
  Building:
    from: [Backlog]
    done: Review
    then: Reviewing
    per: repository
    modes: [UNATTENDED]
    order: "Build {issues} of {story}."
    models: {claude: claude-opus-5-5}
    role: author
  Reviewing:
    from: [Building, Reworking]
    done: Review
    blocked: Reworking
    per: repository
    modes: [UNATTENDED]
    order: "Review what {tasks} made for {issues}."
    models: {claude: claude-opus-5-5}
    role: reviewer
  Reworking:
    from: [Reviewing]
    done: Review
    then: Reviewing
    per: repository
    modes: [UNATTENDED]
    order: "Fix what was found in {issues} of {story}."
    models: {claude: claude-opus-5-5}
    role: author
```

| Role | End | Workspace | Services | Account | [Rights](https://porelo-ai.github.io/core/rights/#the-four-parts-of-a-start) |
|---|---|---|---|---|---|
| author | push | own | yes | author | push, reply |
| reviewer | points | foreign | no | review | reply, openThreads |
| tester | findings | foreign | no | review | openThreads, report |
| screener | points | foreign | no | review | reply, openThreads, reject |

## What fails the load

A file that is missing, is no YAML or has no `work` mapping leaves no rule, with one fault. Otherwise a role or a
rule that cannot load is left out with its fault, and every other role and rule still loads. Such a fault starts
with `stories/work.yml:`, then `role <name>:` for a role or `<working state>:` for a rule; a role is told the first
of its faults.

- A rule: `role is missing`, `role '<name>' is not defined under roles`, or `role '<name>' did not load` beside
  that role's own fault.
- A role named `operator` in any case: `the name operator is taken by the person at the machine`.
- A role without one of its parts: `end is missing`, `workspace is missing`, `rights is missing`.
- An unknown word: `unknown key '<key>'`, `end '<word>' is none of push, findings, points and result`,
  `workspace '<word>' is none of own, foreign, readOnly, allRepositories and none`, `writeAs '<word>' is neither
  author nor review`, `right '<word>' is none of push, reply, openThreads, resolveThreads, start, stop, done, report
  and reject`.
- Checked against the workflow, a rule: `the role '<name>' <end>, so the rule names blocked`,
  `the rule names blocked, and the role '<name>' <end>`, `blocked '<state>' is no work on what was found: its role
  '<name>' <end>`, `the role '<name>' <end>, so the rule names no then`, `then '<state>' reads no work: its role
  '<name>' <end>`, and `a rule with a result names no then and no blocked`.
- A rule a role breaks: `breaks <word> (<plain words>)`, naming every rule broken. The first five words are
  [Porelo's own rules](https://porelo-ai.github.io/core/rights/#the-rules-porelo-keeps-itself), which its Start
  would refuse; the planner adds the other ten.

| Word | The role holds |
|---|---|
| `foreignWorkLeadsOut` | another task's work with the project's services or push |
| `foreignWorkNamesNoRights` | another task's work with no right at all |
| `rejectWithPush` | reject together with push |
| `rejectOverOwnWork` | reject without another task's work |
| `pushWithoutOwnWork` | push outside own work |
| `endNeedsPush` | an end that pushes work without push |
| `pushNeedsEnd` | push with an end that pushes no work |
| `pointsNeedAnothersWork` | an end that gives points without another task's work |
| `rejectNeedsPoints` | reject with an end that gives no points |
| `findingsNeedThreads` | findings at another task's work without openThreads |
| `endNeedsReport` | an end that reports findings or hands back a result without report |
| `foreignWorkNeedsReading` | another task's work with an end that neither reports findings nor gives points |
| `findingsNeedLandedWork` | findings in its answer without readLanded |
| `readLandedNeedsWorkspace` | readLanded without a workspace |
| `pointsNeedThreads` | points without openThreads and without reject |

## Read only from the default branch

The planner reads `stories/work.yml` only from the default branch of the planning repository, even when the project
names another branch. A role changed on any other branch, a task's branch among them, changes nothing a task may do
until a person lets the change onto the default branch. That is why the file needs no signature.

## Four eyes

A task of a role that reports findings, gives points or holds a judgment is always another task than the one that
built the work it reads; the planner refuses its start under that task's name.

## Manual issues and the person at the machine

For an issue whose worker is `MANUAL`, the planner starts no task and offers no role. A person moves the issue, and
the stage record names the move as a person's. The person at the machine is held to no role.
