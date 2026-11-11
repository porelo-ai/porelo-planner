# PL28 — The Planner's Own Files On Windows

```json
{
  "$schema": "issue-1.json",
  "id": "PL28",
  "title": "The Planner's Own Files On Windows",
  "state": "Implementing",
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

On Windows, the POSIX mode is replaced by the file's owner and its discretionary access control list (DACL):

- **Trusted principals** are the account itself, `SYSTEM` (S-1-5-18) and the local Administrators group
  (S-1-5-32-544). Like root on Linux, they can read everything anyway.
- **What the planner creates** - each directory, `planner.json`, the locks and the record files - gets an explicit
  DACL with one entry, full control for the account. Directories also get the inheritance flags, so new files get the
  same entry.
- **A directory or file of the planner's own** is accepted only if:
  - its owner is the account;
  - no entry grants any right to a principal other than the trusted three;
  - it is no reparse point (no symbolic link and no junction);
  - a file has exactly one link.
- **An ancestor** (`%LOCALAPPDATA%` and every directory above it) is accepted only if:
  - its owner is a trusted principal or `TrustedInstaller`;
  - no other principal holds `DELETE`, `WRITE_DAC` or `WRITE_OWNER` on it, or `FILE_DELETE_CHILD` on it.

  This is the counterpart of "root's or mine, and others may write only where it is sticky": the root of a drive lets
  every account create folders, but no account can rename or delete another's.
- The account is read from the process's own token, never looked up by `user.name`, which another domain's account
  of the same name could match.

## Plan

- **One type for both platforms.** A `PrivateFiles` interface in `store/git` (which `app` already depends on), with a
  POSIX and an ACL implementation chosen from the file system's supported attribute views. It offers creating an
  owner-only directory or file, checking one, and checking an ancestor.
  - `AddressFile`, `WorkRecord` and `CacheDirectory` each carry their own copy of today's POSIX checks. These copies
    move into the POSIX implementation unchanged, and the three classes call the interface.
  - The base directories are chosen in one place: `XDG_*` and `HOME` on Linux, `LOCALAPPDATA` on Windows.
- **Git on Windows.** `GitCall` sets `GIT_ASKPASS=/bin/false`, `GIT_EDITOR=:` and an `ssh` command. Git for Windows
  must still fail a login prompt at once, without a dialog of Git Credential Manager. The values that do so are
  measured on the Windows runner (see below) and set per platform. `git` is found on `PATH` as today.
- **Locks.** `FileChannel.lock` is mandatory on Windows, and replacing or deleting an open file fails there. The
  address file's replace (`ATOMIC_MOVE`) and delete on stop, and the work record's replace, are retried briefly on a
  sharing violation and then fail loud.
- **Tests, red first.**
  - **Unit tests over the ACL implementation** run on Linux through a fake view, since an `AclFileAttributeView` is an
    interface. They cover a foreign entry, a foreign owner, a reparse point, a second link, an ancestor that another
    account may rename, and the trusted three accepted.
  - **The same cases on real files** are tagged `windows` and run on the Windows runner, which the Windows build
    issue adds.
  - Today's POSIX tests are tagged `linux` where they need POSIX, so the Windows run skips them by name.

## Acceptance

- On the Windows runner, the planner starts, writes `%LOCALAPPDATA%\porelo-planner\planner.json` with the DACL above,
  and refuses to start when `porelo-planner` grants another account read access. Each refusal is seen in a test.
- Every Linux test that ran before still runs and passes on Linux, unchanged in what it asserts.
- Each new guard is seen red by a break that compiles.

## To be checked

- The default owner and DACL of `C:\`, `C:\Users`, the profile, `AppData` and `AppData\Local` on the runner's
  `windows-2025`, so that the ancestor rule accepts a fresh account and refuses a changed one.
- Whether `AclFileAttributeView.setAcl` writes a DACL without inherited entries in native image too, and whether a
  later change of the parent's DACL adds inherited entries back (the check then refuses, loud).
- Which `GIT_ASKPASS` and `GIT_SSH_COMMAND` values make Git for Windows refuse a prompt at once.
- How the process's own account SID is read in Java without a lookup by name (the owner of a file it has just created,
  or `whoami /user` by full path).
