# PL27 — The Work Record Says Why It Is Unavailable

```json
{
  "$schema": "issue-1.json",
  "id": "PL27",
  "title": "The Work Record Says Why It Is Unavailable",
  "state": "Review",
  "size": "S",
  "priority": "Medium",
  "rank": 16,
  "blockedBy": [],
  "covers": "A refusal of the local work record names what was refused, such as a directory another account could replace, so the person can fix it instead of seeing only that the record is unavailable"
}
```

**Size:** S - `WorkRecord` and a small `LocalPaths` in `app` with their tests; the checks in `PrivateFiles` already
say why, the answer keeps its shape, and only the words of the contract's description of `RecordUnavailable` change.

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
   is not valid JSON.", "The work record does not have the record's form.", "The work record is not a record of
   version 1 for `<project>`." and "The work record has a run that fails its check." for what `read` and `validate`
   refuse; the failed step with the system's words for the lock, `write` and `close`; "was closed" and "is not open"
   for an entry no longer or not yet held. `PosixPrivateFiles` says "its group or others can write" where a group
   may not write either, and "others can write" only above the clones. A parser's own message is never passed on, since it can quote the
   record's content. Red first, unit in `WorkRecordTest`: `aWritableAncestorCannotRedirectTheRecord` asserts a reason
   naming the writable directory, `aSecondPlannerCannotWriteUntilTheFirstCloses` one naming the other planner,
   `unreadableOrLostRecordsNeverInventAZeroCountRun` the JSON case. Harm: a project stopped with no way for the
   person to find what to fix.
2. **The contract's words** (`openapi/planner.yaml`, `RecordUnavailable`): "The record reason contains fixed words
   only" becomes what the reason now holds: fixed words and the refused directory or file, named relative to the
   home directory or to the state directory (`XDG_STATE_HOME`, `%LOCALAPPDATA%` on Windows), never as an absolute
   path, and never the record's content; the schema stays. `Refusal`'s detail is "free of any credential or absolute
   local path". Unit:
   `ApiMatchesDescriptionTest` stays green.
3. **No absolute path in a reason.** `Refusal` says its detail is "free of any credential or local path", and the
   check's words name absolute directories under the person's home. Each path in a reason is written relative to the
   base it was resolved from: `$XDG_STATE_HOME/...` where the state directory came from `XDG_STATE_HOME`, otherwise
   `~/...` for a path under the home directory; a path under neither is not named, only what was wrong with it. On
   Windows the bases are `%LOCALAPPDATA%` and `USERPROFILE` as `~`, matched in any case. Control and format
   characters in a reason are replaced. `LocalPaths` (`app`) does this, the deepest base first, a base matched only
   up to a separator or the path's end. Red first, unit in `WorkRecordTest`:
   `aGroupWritableHomeIsNamedFromTheHomeAndNeverByItsAbsolutePath` - a home its group can write is named as `~`, and
   its absolute path appears neither in the reason nor in the refusal's detail; `LocalPathsTest` for the bases, a
   sibling named like the home, a path under no base, Windows paths and control characters. Harm: the account's
   name and the home's layout handed to whatever reads or logs the answer.

Read in `WindowsPrivateFiles` and `WindowsAccess`: they word each refusal as plainly as `PosixPrivateFiles`, naming
an account by its SID. A path under no base goes whole, a word after a space that holds a separator included;
line and paragraph separators, private-use characters and lone surrogates are replaced like control characters. A
failed write's reason is not replaced by a failed removal of its temporary file. What is left goes to
[PL75](README.md#pl75). The screen shows a refusal's `reason` (`planningFailureWords`).

## Acceptance

- The red-first tests of steps 1 and 3 seen red on today's code, then green; the app's whole suite green.
- A planner started with a home its group can write answers the window's first `PUT /projects/{project}` with a
  `WorkRecordUnavailable` whose reason names that directory as `~/...` and says that its group or others can write
  it; the home's absolute path appears nowhere in the answer.

## To be checked

- Whether the window shows the reason of a refused `PUT /projects/{project}` where the person sees it, or only a
  generic failure, read in the window's handling of the planning screen's problem text.
