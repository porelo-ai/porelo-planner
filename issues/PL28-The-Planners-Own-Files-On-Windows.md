# PL28 — The Planner's Own Files On Windows

```json
{
  "$schema": "issue-1.json",
  "id": "PL28",
  "title": "The Planner's Own Files On Windows",
  "state": "Review",
  "size": "M",
  "priority": "Medium",
  "rank": 17,
  "blockedBy": [],
  "covers": "On Windows the planner keeps its address file, work record and clones under %LOCALAPPDATA%\\porelo-planner, each closed to other accounts through its access control list, with the same refusals as on Linux"
}
```

**Size:** M - three modules' file handling behind one new type, a second platform's checks and their tests; the
address file's place is a contract the window reads.

**What must be true.** On Windows, the planner keeps the files only its account may read or change in a directory of
that account, closed to every other account: the address file with its token, the local work record and the clones.
A directory or file another account could read, replace or redirect is refused as it is on Linux, and the refusal
says which path and why.

## Where the files go

| What | Linux | Windows |
|---|---|---|
| The address file and its lock | `$XDG_RUNTIME_DIR/porelo-planner/planner.json` | `%LOCALAPPDATA%\porelo-planner\planner.json` |
| The work record | `$XDG_STATE_HOME/porelo-planner/<project>/` | `%LOCALAPPDATA%\porelo-planner\state\<project>\` |
| The clones | `$XDG_CACHE_HOME/porelo-planner/<project>/` | `%LOCALAPPDATA%\porelo-planner\cache\<project>\` |

- `%LOCALAPPDATA%` is the account's own local, non-roaming application data, which no other non-administrative
  account can write by default. It is read from the environment and must be an absolute path on a drive (`C:\...`);
  a UNC path, a relative path or `..` is refused, as a relative `XDG_*` path is today.
- A packaged app's new files under `AppData` may go to a private place of its package that the window does not see.
  The Windows build's install check decides whether the packaged planner keeps this place, with write virtualization
  turned off, or the address file moves; a move is agreed with the window in the channel.
- The property `planner.runtime-directory` still overrides the base of the address file on both platforms; the mock
  mode keeps needing a runtime directory of its own.
- The address file lives beside the state and the clones, not in a directory cleared at sign-out as on Linux. A dead
  planner's `planner.json` is already deleted under the lock at the next start, so a stale one is never served.

## Owner-only through access control lists

On Windows, the POSIX mode is replaced by the owner and the discretionary access control list (DACL):

- **Trusted principals** are the account itself, `SYSTEM` (S-1-5-18) and the local Administrators group
  (S-1-5-32-544). Like root on Linux, they can read everything anyway; an elevated process makes what it creates the
  Administrators' own.
- **What the planner creates** - each of its directories - gets an explicit DACL of one entry, full control for the
  account, inherited by directories and files made in it and by nothing from above. Its files take that one entry.
- **A directory of the planner's own, and the base `%LOCALAPPDATA%`,** is accepted only if:
  - its owner is a trusted principal;
  - no entry grants any right to another principal, even one that applies only to what is made below;
  - it is no reparse point (no symbolic link, junction or mount);
  - for the planner's own, the account's full control passes to everything made in it.
- **An ancestor** (every directory above the base) is accepted only if its owner is trusted or `TrustedInstaller`, it
  is no reparse point, and no other principal holds `DELETE`, `WRITE_DAC`, `WRITE_OWNER`, `FILE_DELETE_CHILD`,
  `GENERIC_ALL` or `GENERIC_WRITE` on it itself. This is the counterpart of "root's or mine, and others may write only
  where it is sticky": the root of a drive lets every account create folders, but none rename or delete another's.
- **Files are not checked one by one**, and a second link is not refused on Windows: no other account can make, link
  or replace an entry in a directory that is the account's alone, and a hard link needs write access to its target.
- **Read by SID, never by name.** Java names accounts and groups only in the system's language. One Windows
  PowerShell call, taken by its full path from the system directory, sets the DACLs and prints each path's owner and
  entries by SID, with the account's own SID from the process's token. The paths reach it through its environment,
  never as code. A file system without DACLs, such as FAT32, fails that call, and the planner refuses to start.

## Plan

- **One type for both platforms.** `PrivateFiles` in `store/git`, with `PosixPrivateFiles` (today's checks, moved
  unchanged) and `WindowsPrivateFiles` (the PowerShell call) beside `WindowsAccess` (the answer's form and the rules
  above). `AddressFile`, `WorkRecord` and `CacheDirectory` call it; the bases are chosen in one place: `XDG_*` and
  `HOME` on Linux, `LOCALAPPDATA` on Windows.
- **Git on Windows.** `GitCall` removes every `GIT_*` variable, `SSH_ASKPASS`, `SSH_ASKPASS_REQUIRE` and
  `GCM_INTERACTIVE` in any case, and points `core.hooksPath` and `GIT_ASKPASS` at
  `%LOCALAPPDATA%\porelo-planner\none`, a name no other account can create, so no hook runs and a login prompt fails
  at once. `git` is found on `PATH` as today.
- **Locks.** Replacing or deleting an open file fails on Windows; the address file's delete at start, its replace
  and its delete on stop, and the work record's replace are retried for two seconds on a sharing violation, then
  fail loud. The work record skips the directory's `fsync`, which Windows does not offer.
- **Tests, red first.**
  - Unit tests on Linux over a fake PowerShell answer: a foreign entry, an inherit-only one, a foreign owner, an
    Administrators' owner, a reparse point, an entry that does not pass on, an ancestor another account may
    re-permission or delete below, a base another account may add to, an answer out of its form; the drive path's
    form; Git's variables in any case.
  - The same rules on real files, tagged `windows`: the directories made and passing again, an entry for Users
    refused, a junction refused. They run on the Windows runner through the workflow *Windows trial*, on a branch under
    `windows/`, until the Windows build's job runs them on every push.
  - Tagging today's POSIX tests `linux`, so a Windows run skips them by name, belongs to the Windows build.

## Acceptance

- The tests tagged `windows` pass on the Windows runner, and each refusal is seen there.
- Every Linux test that ran before still runs and passes on Linux, unchanged in what it asserts.
- Each new guard is seen red by a break that compiles.

## Measured

- On `windows-2025` the three tests on real files pass in the runner's temporary directory, so the ancestors of that
  directory, as the runner sets them, pass the rule. Which directory that is, and the owner and DACL of the profile's
  `AppData\Local`, the run did not print; the executable's first run on the runner reads them.
