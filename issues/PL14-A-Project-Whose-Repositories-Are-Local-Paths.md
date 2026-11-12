# PL14 — A Project Whose Repositories Are Local Paths

```json
{
  "$schema": "issue-1.json",
  "id": "PL14",
  "title": "A Project Whose Repositories Are Local Paths",
  "state": "Backlog",
  "size": "M",
  "priority": "Low",
  "rank": 2,
  "blockedBy": ["PL42"],
  "covers": "Planning a project whose own repository and project.yml repositories are absolute local paths in one directory, any other path refused, such as Porelo's Default project or a local checkout"
}
```

**Size:** M - `store/git` and `app` with their tests, and one contract change: `PUT /projects/{project}` takes an
absolute local path as `repository` beside a clone URL, which the window must send.

**Estimate:** 67 min, the measured median of M. Uncertain: the form of the Default project's paths on Windows is not
yet known, and how [PL42](README.md#pl42)'s link pattern reads for a `project.yml` that names no web host.

**What must be true.** A person plans a project whose repositories Porelo names by an absolute local path rather
than a clone URL, as Porelo's Default project and a local checkout do; today the planner refuses such a project as
`NotACloneUrl`, and the window offers no planning for it. A local project's `project.yml` reaches only repositories
in the same directory as the own repository; any other path is refused.

## Plan

What [PL42](README.md#pl42) already gives and this issue does not repeat: every central given as a clone URL -
`https://`, `ssh://` or `user@host:path` - on any host and owner, and the link pattern of story texts and indexes read
from `project.yml`. What this issue adds is the one form still refused after it, an absolute local path, for the own
repository and for the others, and the Git calls that may then reach the local filesystem.

Today, read in the code:

- `CloneUrl` (`store/git`) refuses a local path on purpose; `CloneUrlTest` holds `/srv/git/store.git` and
  `file:///...` as refused, since `project.yml` can be changed by anyone with write access to the store.
- `StoreConfiguration.checkedSource` (`app`) turns that refusal into `NotACloneUrl`; `StoreConfigurationTest` and
  `BoardReadsTest.aRefusedSourceIsNotACloneUrlAndLeavesNoProject` assert it for `/home/person/git/p`.
- `GitCall.SAFE_CONFIG` sets `protocol.allow=never` and allows only `https` and `ssh`. Measured: a fetch from a local
  path then fails with "transport 'file' not allowed" (exit 128); with `protocol.file.allow=always` the shallow fetch
  `GitRemote.fetch` runs succeeds, and a push to a local bare repository lands. A push to the checked-out branch of a
  working checkout is refused by Git itself ("branch is currently checked out").
- `ProjectFile.name` takes the last part after `/` or `:`; a Windows path needs `\` too.
- The `stories` reader of `project.yml` is already part of `store/git` (`stories.Project`), so the original question
  about another library taking a local path is moot: the change stays in this repository.

The shape:

- A sealed `Address` in `store/git` with two forms, `CloneUrl` unchanged and a new `LocalPath`: absolute and normal
  (no `.` or `..` part, no `file://`), on Linux starting with `/`, on Windows a drive path; refused otherwise. Its
  `toString` names no path. `Remote`, `Repository`, `GitProjects.open` and `GitRemote` take an `Address`.
- `GitCall` adds `protocol.file.allow=always` only to a call whose address is a `LocalPath`; every other call keeps
  file transport refused.
- A local path is taken only where the person chose it: as the window's own source, and in `project.yml` only when
  the own repository is itself local. A project fetched from a clone URL whose `project.yml` names a local path is
  refused as `Unreadable`, naming the repository key.
- **The guard on a local project's reach.** A local path in `project.yml` is taken only when its parent directory is
  the own repository's parent directory, compared on the normal paths; any other absolute path, a deeper one in that
  directory included, is refused as `Unreadable`, naming the repository key and no path. The check sits in
  `ProjectFile`, where `project.yml`'s repositories are turned into addresses, before any Git call reaches them.
- A save to a working checkout whose branch is checked out is refused as Git refuses it, told in the planner's words;
  the planner never sets `receive.denyCurrentBranch` nor touches a working tree.
- `StoreConfiguration` takes either form; `NotACloneUrl` stays the refusal's name for anything else. `planner.yaml`
  says `repository` is a clone URL or an absolute local path.

Tests, each with its level and the harm it prevents:

- Red first, integration in `store/git` (real `git` in a temporary directory, `GIT_CONFIG_GLOBAL=/dev/null`): a local
  project of two bare repositories is read and a move is saved and pushed into the own one. Fails today with the
  `CloneUrl` refusal. Harm: a local project shows nothing or loses a save.
- Unit, `ProjectFileTest` over its fake remote: a project fetched from a clone URL whose `project.yml` names a local
  central is refused. Harm: someone with write access to a shared store makes the planner read or push into the
  person's own local repositories.
- Red first, unit, `ProjectFileTest` over its fake remote: a local own repository at `/work/p/own` whose
  `project.yml` names `/work/p/other` is read; one naming `/home/person/secret/repo`, `/work/other/repo` or
  `/work/p/own/sub` is refused, and its fake remote is never asked for it. Fails today, since no such check exists.
  Harm: a change to `project.yml` points the planner, which pushes with the person's rights, at another of the
  person's repositories. Seen red by turning the parent comparison off.
- Unit, `GitCallTest`: a call for a clone URL keeps `file` transport refused; only a local call allows it. Harm: a
  remote reaches the local filesystem through Git.
- Unit, a `LocalPath` test beside `CloneUrlTest`: relative, `..`, `file://`, an option and a Windows path on Linux are
  refused. Harm: Git reads an option or a path the person did not name.
- Integration in `store/git`: a save to a checked-out branch is refused and the working tree is unchanged. Harm: the
  person's uncommitted work lost.
- `StoreConfigurationTest` and `BoardReadsTest`: the local-path cases change from refused to taken, a relative path
  stays refused; their old assertion is replaced, not kept.

The window's part, built in its own repository: it offers planning for a project whose own repository is a local
path and sends that path as `repository`.

## Acceptance

- A local project of bare repositories is read and a move pushed into it, seen in the repository's log.
- A remote project naming a local central, a local project naming a repository outside its own repository's
  directory, a relative path, and a save into a checked-out branch are each refused, each test seen red once.
- The contract in `planner.yaml` names both forms, agreed with the window's repository before it changes.

## To be checked

- Which paths Porelo writes for the Default project, on Linux and Windows, and whether its repositories are bare; to
  be asked of the core repository's agent.
- How [PL42](README.md#pl42)'s link pattern reads for a `project.yml` with no web host, once it is in `main`.
- The plan read by a reviewer of another model before the build, since it opens file transport in `GitCall`.
