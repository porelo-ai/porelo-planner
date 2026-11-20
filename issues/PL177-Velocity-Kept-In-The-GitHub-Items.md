# PL177 — Velocity Kept in the GitHub Items

```json
{
  "$schema": "issue-1.json",
  "id": "PL177",
  "title": "Velocity Kept in the GitHub Items",
  "state": "Analyzed",
  "size": "L",
  "priority": "High",
  "rank": 1,
  "blockedBy": [],
  "covers": "A project kept in GitHub Issues stores a task's measurements in its items' fields and nothing in Git; the planner computes each size's median per repository and over the whole project from the finished items, starting from a seed per repository and size in project.yml"
}
```

**Why this size.** L, not M: a contract another repository reads (the seed's key in `project.yml`, the new fields'
names), a new answer in `planner.yaml`, the GitHub store's writes and reads of the fields, and the rule in `core`
that picks a figure; not XL, since the sums the median needs are already written today.

**What must be true.** In a project kept in GitHub Issues nothing about issues is stored in Git, its velocity
included; the person and the agents see what a size takes, per repository and over the project, from the items
themselves.

## Plan

- **Today**: the median exists only in the Markdown store, `stories stage median|p80` (`StageCommand`,
  `StageRecords.statistic`), over `stats/seed.csv` and `stats/<id>.jsonl`; an estimate stage writes it as
  `estimate_min`. The GitHub store already keeps an item's sums in the number fields `Minutes`, `VM minutes`,
  `Tokens`, `Stages` and its last estimate in `Estimate`, added up at each stage's save (`FieldsBoardWriter`,
  `StageComment.sums`); `core`'s `Velocity` over Porelo's task records has no caller.
- **The fields**, added to what the planner makes on a project: `Started` and `Ready`, text fields holding the UTC
  time of the item's first measured stage and of its move to a closed state, each written once, in the same call
  as the state; `Size` and `Minutes` stand as today. A GitHub date field holds no time, so both are text, checked
  as an ISO instant on reading; a value that is no instant is a fault of the item, not a sample. The names are
  agreed with Agent Core and Agent Machines before the build.
- **The seed**: `project.yml` gains `velocity.seed`, a map repository -> size -> minutes, written by the carry-over
  ([PL168](README.md#pl168)) from the current medians and changed by the person afterwards, signed like any change to
  `project.yml`. The key and its shape are agreed with Agent Core, which owns the file's schema.
- **The figures** (`core`, plain Java, `VelocityFromItems`): a sample is a finished item (a closed state of the
  workflow) with a size and `Minutes` > 0. Per size it answers the median of each repository's samples, the median
  over every repository's samples on the project's board, and the counts. A forecast for an item takes, in order:
  the repository's median when the repository has at least 5 samples of that size; else the project's median when
  the project has at least 5; else the repository's seed for that size; else the median of the seeds of that size
  over the project's repositories; else none, said as such. Each answer names which one it took. The threshold of
  5 measured items of a size is the operator's.
- **The answer**: `GET /projects/{project}/velocity` in `planner.yaml`, per size the repository medians, the
  project median, the counts and the seeds; `porelo-planner velocity median|p80 <size> --project <dir>` answers the
  same on the command line, in place of the Markdown store's `stories stage median|p80`; an estimate stage writes
  the forecast into `Estimate`. There is no Markdown branch: the Markdown store goes
  ([PL178](README.md#pl178)).
- Red first: `VelocityFromItemsTest` - the fallback order at 4 and 5 samples, a repository with none taking the
  project's, a project with none taking the seed, an open item and one of `Minutes` 0 left out, a `Ready` that is no
  instant a fault; a GitHub store test that `Started` and `Ready` are written once and in the state's call; a
  controller test of the answer; a `project.yml` test refusing a seed of an unknown size or of minutes < 1.

## Acceptance

- On the test organization's project, after two items of one size are carried and finished, the velocity answer
  shows their median per repository and over the project, and a third item's estimate stage writes the figure the
  rule picks; no file under `stats/` is written.

## To be checked

- The names `Started`, `Ready` and the key `velocity.seed`, with Agent Core and Agent Machines in the room.
