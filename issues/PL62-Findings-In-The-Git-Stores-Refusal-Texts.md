# PL62 — Findings In The Git Store's Refusal Texts

```json
{
  "$schema": "issue-1.json",
  "id": "PL62",
  "title": "Findings In The Git Store's Refusal Texts",
  "state": "Review",
  "size": "S",
  "priority": "Low",
  "rank": 1,
  "blockedBy": [],
  "covers": "A refusal of the Git store scrubs a local path on Windows too, keeps the field a fault names, such as /size, instead of taking it for a path, says which forms a central must take when it refuses one, and quotes a repository name it does not know"
}
```

**Size:** S - one module, `planner/store/git`, and its unit tests; no contract changes, since a refusal's words are
free text in the contract.

**Estimate:** 33 min, the measured median of S. Uncertain: which forms a Windows path takes in Git's and Java's
words there, and whether the pattern for them takes something that is not a path.

**What must be true.** A refusal the Git store hands the window names no local path on any system the planner runs
on, still names the field a fault concerns, and says plainly which value it refuses.

## Findings

Small findings, no harm beyond a misleading or missing text, except the first, which lets a local path through.

- **Windows paths pass the scrub.** `GitRunner.scrubbed` replaces local paths by the pattern `LOCAL_PATH`
  (`file://...` and `~/...` or `/...` not after a word character), which knows only Unix paths: a drive path with
  backslashes, such as a scratch directory `C:\Users\<account>\AppData\Local\Temp\...`, or a UNC path `\\host\share`,
  passes it whole; `C:/Users/...` leaves `C:` and is scrubbed. `GitStore.local` takes the save's scratch out only as
  `scratch + "/"` or the bare `scratch`, so on Windows its `\` after the scratch stays.
- **A JSON pointer is taken for a path.** `GitStore.refuseFaults` passes the planning index's first fault through
  `GitRunner.scrubbed`; a fault such as `PL<nn>-X.md: /size: a dash, but Review needs a size` (`IssuesIndex`, and the
  schema faults `<pointer>: <found> found, <expected> expected` of `IssueSchema` and `StorySchema`) reaches the window
  as `PL<nn>-X.md: <path> a dash, ...`. The faults of `IssuesIndex.write` name files relative to `issues/`, as
  `IssuesIndex.Result` says, so they hold no local path the path pattern must catch.
- **A refused central names no value.** `stories.Project.repository` refuses a central it does not take as
  `<where>: unsupported repository central <value>`; for `file:///x` or `/srv/x` the scrub in `ProjectFile.read`
  and in `GitStore`'s save (`local(e, scratch)` on the `IllegalArgumentException` of the index write) turns this into
  `... central <path>`, so the person reads neither what is wrong nor what is taken.
- **An unknown repository name stands unquoted.** `stories.Project.central(name)` throws
  `<name> is not among the repositories`, and the compact constructor `own repository <own> is not among the
  repositories`, the name unquoted, so an empty or blank name reads as a broken sentence.

## Plan

All in `planner/store/git`; every test a unit test, run with `./mvnw -B -s settings.xml -pl planner/store/git test`.

1. `GitRunner.LOCAL_PATH` gains a drive path (`[A-Za-z]:` then `\` or `/`, not after a word character) and a UNC
   path (`\\` then a host); `GitStore.local` takes the scratch out followed by either separator. Red first, in
   `GitRunnerTest`: a failure quoting `C:\Users\person\AppData\Local\Temp\p\r.git`, `c:/Users/...`, `/c/Users/...`
   or `\\host\share\person\r.git` contains neither `person` nor `host\share` and holds `'<path>'`; it still holds
   `https://git.example.org/a/b` and `stories/README.md`. Harm prevented: the account name and home directory reach
   the window and its log.
2. `GitStore.refuseFaults` takes the scratch and words the fault by `GitStore.faultWords`: the scratch removed as
   `local` does and secrets taken out by `GitRunner.withoutSecrets`, split out of `GitRunner.scrubbed`, but no path
   pattern. Red first, in `GitStoreSaveTest`: a save refused for a finished issue with a dash size holds
   `AB03-Own.md: /size: a dash, but Finished needs a size`, no `<path>` and not the test's directory. Harm
   prevented: the field to fix is dropped from the refusal. `FaultWordsTest` guards what stays of the scrub there:
   the scratch removed after either separator, and a password taken out while a pointer stays. Harm prevented: the
   person's home or a credential reaches the window.
3. A central `project.yml` names in a form the library does not take is already refused by `stories.Central.read`
   naming the forms it takes (`https://host/path, ssh://host/path or user@host:path`); the value itself, when it is
   a local path such as `file:///x`, stays scrubbed to `<path>`, since a local path in a refusal is the harm step 1
   guards. Nothing is changed here.
4. `stories.Project.central` and the compact constructor quote the name in its shown form:
   `'<name>' is not among the repositories`, `own repository '<own>' is not among the repositories`.
   `PublicReadersTest` held the old text verbatim; that `hasMessage` is dropped, its `isInstanceOf` stays.

Git for Windows echoes a path as `C:/...`, Java as `C:\...`, and the MSYS shell as `/c/...`; the pattern covers all
three and a share's path, so which one reaches a refusal does not matter. The callers of `GitRunner.scrubbed` pass
Git's, the file system's and `project.yml`'s refusals; in none of them does a drive-like `X:\` or `X:/` stand for
anything but a path.

- Built: the drive and share paths in `GitRunner.LOCAL_PATH`, the scratch taken out after either separator,
  `GitRunner.withoutSecrets` and `GitStore.faultWords` for the planning index's faults, and the quoted repository
  names in `stories.Project`; with `GitRunnerTest`'s Windows cases, `FaultWordsTest` and the save test, each seen
  red by a deliberate break.
- The code reading's findings, fixed, each with a unit test seen red before the fix and again under a deliberate
  break: a URL's or an scp-like address's user info is taken up to the last `@` before the host whatever it holds
  short of a blank, so a password with a quote goes; an `Authorization:` or `Proxy-Authorization:` header keeps
  only its scheme and loses everything after it to the line's end, a Digest's response included; a quoted local
  path is taken whole, blanks and all; an unquoted Windows drive or share path, `\\host` or `//host`, runs to the
  line's end or to the `': ` that closes a path in Git's messages, since a blank in an account's name does not end
  it; a planning index's fault loses a quoted path, a Windows path, a file URL and a path from the home directory,
  so an unknown property named like a path goes, while an unquoted `/...` stays as the pointer to a field it is
  there.

## Acceptance

- `GitRunnerTest`'s new Windows case is seen red without the drive and share patterns and is green with them; the
  scratch guard of step 2 is seen red by a deliberate break of the scratch removal.
- A save refused for a dash size in Finished shows `/size:` in its refusal; a `project.yml` with `central: file:///x`
  is refused naming the forms taken.
- The module's whole unit suite is green.
