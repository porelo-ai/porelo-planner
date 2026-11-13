# PL62 — Findings In The Git Store's Refusal Texts

```json
{
  "$schema": "issue-1.json",
  "id": "PL62",
  "title": "Findings In The Git Store's Refusal Texts",
  "state": "Implementing",
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
  `GitRunner.scrubbed`; a fault such as `PL12-X.md: /size: a dash, but Review needs a size` (`IssuesIndex`, and the
  schema faults `<pointer>: <found> found, <expected> expected` of `IssueSchema` and `StorySchema`) reaches the window
  as `PL12-X.md: <path> a dash, ...`. The faults of `IssuesIndex.write` name files relative to `issues/`, as
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
   `GitRunnerTest`: a failure quoting `C:\Users\person\AppData\Local\Temp\p\r.git` and `\\host\share\r.git`
   contains neither `person` nor `host\share` and holds `<path>`; it still holds `https://git.example.org/a/b` and
   `stories/README.md`. Harm prevented: the account name and home directory reach the window and its log.
2. `GitStore.refuseFaults` takes the scratch, removes it as `local` does and takes secrets out, but no longer runs
   the path pattern; `GitRunner` splits its secret part into a method of its own for that. Red first, in
   `GitStoreSaveTest`: a save refused for an issue in Review with a dash size names no scratch directory (the
   guard that stays after the path pattern is dropped there); that `/size:` now stands in it is seen once by hand,
   not asserted.
3. `stories.Project.repository` says `<where>: central is not git@github.com:porelo-ai/<name> or
   https://github.com/porelo-ai/<name>` (with `upstream` where that key is read), naming the forms it takes instead
   of the value. No test: the text only reads wrong.
4. `stories.Project.central` and the compact constructor quote the name: `'<name>' is not among the repositories`.
   [PL42](README.md#pl42), built now, passes `Project`'s names through its safe form; whatever of this it leaves is
   done here.
   `PublicReadersTest` holds the old text verbatim; that `hasMessage` is dropped, its `isInstanceOf` stays.

## Acceptance

- `GitRunnerTest`'s new Windows case was seen red before step 1 and is green after; the scratch guard of step 2 was
  seen red by a deliberate break of the scratch removal.
- A save refused for a dash size in Review shows `/size:` in its refusal; a `project.yml` with `central: file:///x`
  is refused naming the forms taken, with no `<path>`.
- The module's whole unit suite is green.

## To be checked

- Which forms Git for Windows uses when it echoes a path (`C:/...`, `C:\...`, `/c/...`), read on the Windows
  runner's Git or its documentation, so the pattern covers what reaches a refusal there.
- That no other caller of `GitRunner.scrubbed` passes a text in which a drive-like `X:\` or `X:/` is not a path.
