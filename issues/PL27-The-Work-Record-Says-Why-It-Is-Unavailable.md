# PL27 — The Work Record Says Why It Is Unavailable

```json
{
  "$schema": "issue-1.json",
  "id": "PL27",
  "title": "The Work Record Says Why It Is Unavailable",
  "state": "Backlog",
  "size": "S",
  "priority": "Medium",
  "rank": 16,
  "blockedBy": [],
  "covers": "A refusal of the local work record names what was refused, such as a directory another account could replace, so the person can fix it instead of seeing only that the record is unavailable"
}
```

**Size:** S - `WorkRecord` in `app` and its test; the checks in `PrivateFiles` already say why, the answer keeps its
shape, and only the words of the contract's description of `RecordUnavailable` change.

**What must be true.** When the planner refuses its local work record, the refusal says why in words the person can
act on. Examples are a state directory that another account can write, a link in its path, a record that is not
readable, or another planner holding the lock. Today every such case answers only "The work record is unavailable."
For example, a home directory that its group can write stops every project, and nothing says why. A reason names a
local path only relative to the home directory or to `XDG_STATE_HOME` (`~/...` or `$XDG_STATE_HOME/...`), never as
the absolute path, so no account name reaches an answer.

## Plan

Read in today's code: `WorkRecord` (`app`) answers every refusal with `unavailable(project)`, which is
`WorkRefusal.recordUnavailable(project, "The work record is unavailable.")`; `Refusal.work` makes it a 503
`WorkRecordUnavailable` with `project` and `reason`, and `PUT /projects/{project}` already answers it, since
`Projects.put` reads the record before a project is known. The checks themselves already say why:
`PosixPrivateFiles` and `WindowsPrivateFiles` (`store/git`) throw with words such as "`<dir>` is a directory others
can write, without the sticky bit", "is reached through a link", "has mode ..., not rwx------" or "belongs to another
account"; `WorkRecord.directory` and `privateFile` carry that message into an `IOException`, and `Entry.acquire`,
`read` and `write` drop it. The screen needs nothing: `planningFailureWords` shows a refusal's `reason` or `detail`,
stripped of control characters. The words "The work record is full." stay as they are: `WorkRun.save` tells a full
record from any other by them.

1. **The reasons** (`WorkRecord`). `unavailable(project, why)` takes the reason, and each refusal names its case:
   the check's own words for the state directory or a record file; "Another planner holds the work record of
   `<project>`." when `tryLock` answers none; "The work record is larger than 16 MB." for its size; "The work record
   is not valid JSON." and "The work record has a run that fails its check." for what `read` and `validate` refuse;
   the failed step for `write` and `close`. A parser's own message is never passed on, since it can quote the
   record's content. Red first, unit in `WorkRecordTest`: `aWritableAncestorCannotRedirectTheRecord` asserts a reason
   naming the writable directory, `aSecondPlannerCannotWriteUntilTheFirstCloses` one naming the other planner,
   `unreadableOrLostRecordsNeverInventAZeroCountRun` the JSON case. Harm: a project stopped with no way for the
   person to find what to fix.
2. **The contract's words** (`openapi/planner.yaml`, `RecordUnavailable`): "The record reason contains fixed words
   only" becomes what the reason now holds: fixed words and the refused directory or file, named relative to the
   home directory or to `XDG_STATE_HOME`, never as an absolute path; the schema stays. Unit:
   `ApiMatchesDescriptionTest` stays green.
3. **No absolute path in a reason.** `Refusal` says its detail is "free of any credential or local path", and the
   check's words name absolute directories under the person's home. Each path in a reason is written relative to the
   base it was resolved from: `$XDG_STATE_HOME/...` where the state directory came from `XDG_STATE_HOME`, otherwise
   `~/...` for a path under the home directory; a path under neither is not named, only what was wrong with it. Red
   first, unit in `WorkRecordTest`: a reason for a group-writable state directory under the home names it as
   `~/...`, and the home directory's absolute path appears in no answer of the record's refusals. Harm: the account's
   name and the home's layout handed to whatever reads or logs the answer.

## Acceptance

- The red-first tests of steps 1 and 3 seen red on today's code, then green; the app's whole suite green.
- A planner started with a home its group can write answers the window's first `PUT /projects/{project}` with a
  `WorkRecordUnavailable` whose reason names that directory as `~/...` and says that its group or others can write
  it; the home's absolute path appears nowhere in the answer.

## To be checked

- Whether `WindowsPrivateFiles` words each of its refusals as plainly as `PosixPrivateFiles`, read in its code.
- Whether the window shows the reason of a refused `PUT /projects/{project}` where the person sees it, or only a
  generic failure, read in the window's handling of the planning screen's problem text.
