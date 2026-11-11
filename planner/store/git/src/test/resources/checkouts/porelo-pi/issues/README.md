# Issues

What is open in this repository, grouped by state in the order Review, Rework, Implementing, Backlog,
Analyzed, Accepted, Idea, Estimated, Finished and Rejected; a group with nothing in it is left out.
Within a group, the order is the priority. The number is identity, not sequence.

## Review

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="pi49"></a>[PI49](PI49-The-Tree-Build-Retries-A-Failed-Download.md) | S | — | the tree build fetches Node, fd and ripgrep again on a 5xx or a broken connection, a few times, before it fails |
| <a id="pi47"></a>[PI47](PI47-Snapshots-Not-Published-On-The-Forge-Come-From-Upstream.md) | S | — | on a forge whose registry holds only its organization's packages, the other Porelo snapshots come from the anonymous repository `MAVEN_UPSTREAM_SNAPSHOTS_URL` names, the forge's token sent to the forge alone, a tag still built from releases only |
| <a id="pi46"></a>[PI46](PI46-A-Task-Has-No-Skills.md) | XS | — | a task has no skills, on any start |
| <a id="pi38"></a>[PI38](PI38-The-Build-Runs-On-The-Local-Forge-As-On-The-Central-One.md) | S | — | the workflows run 1:1 on the local Forgejo and the central forge: snapshots from `MAVEN_SNAPSHOTS_URL`, publishing switched by `PORELO_PUBLISH`, legs asked by kind, green on a local Forgejo |
| <a id="pi41"></a>[PI41](PI41-Snapshots-Resolved-With-A-Read-Only-Token.md) | S | — | on a forge other than the central one, snapshots resolved with the runner's read-only token, the publisher's token in no step, since nothing here deploys to the registry, and a Build without `MAVEN_SNAPSHOTS_URL` refused at its start |
| <a id="pi39"></a>[PI39](PI39-The-Repository-s-Agent-Works-In-A-Porelo-Task.md) | S | [PI38](#pi38) | the repository's agent moves into its own Porelo task, after a dry run on this machine |

## Backlog

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="pi43"></a>[PI43](PI43-Mutation-Testing-With-PIT.md) | S | — | mutation testing by hand: a `pit` profile outside CI with no threshold, and a report sorting survivors into real gaps and harmless ones |

## Idea

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="pi51"></a>[PI51](PI51-The-Bill-Generator-Is-Relocked-Past-Its-Advisories.md) |  | — | the bill generator's lock resolves no release with a published advisory; it never ships, so low in priority |
| <a id="pi50"></a>[PI50](PI50-The-Clean-Up-Says-When-Nothing-Was-Rented.md) |  | — | a leg that failed before resolving the machine tooling rented nothing, and its clean-up says so instead of failing on a missing class |
| <a id="pi48"></a>[PI48](PI48-The-Acceptance-Legs-Run-Side-By-Side.md) |  | — | a Build's acceptance legs run at the same time, two machines held at once instead of one, the Build done about one leg sooner |
| <a id="pi44"></a>[PI44](PI44-A-Copilot-Task-Uses-The-Models-The-Account-Has.md) |  | — | a task on GitHub Copilot runs on a model the account has: no flagless failure on a default it lacks, and `--model` takes every model the account has |
| <a id="pi40"></a>[PI40](PI40-The-Acceptance-Legs-Run-On-The-Host-s-Forge.md) |  | [PI38](#pi38) | the acceptance legs on the host's pool again, once it lends a forge job a machine by kind |
| <a id="pi37"></a>[PI37](PI37-Nothing-The-Agent-Writes-Reaches-The-Next-Pi.md) |  | — | nothing the agent writes in a task reaches a later Pi: its settings, models, MCP servers, extensions, `PATH` |
| <a id="pi33"></a>[PI33](PI33-Tell-Waiting-From-Pi-s-Program-Status.md) |  | — | Porelo tells a waiting Pi from one at rest by what Pi 1 reports of itself |
| <a id="pi45"></a>[PI45](PI45-Depend-On-The-Agent-Protocol-Porelo-Provides.md) |  | — | the agent contract named at install time |

## Finished

| # | Title | What was built |
|---|---|---|
| <a id="pi36"></a>[PI36](README.md#pi36) | Import And Report Pi's Sign-Ins On The Host | `vault import` takes Pi's sign-in for the provider asked for, and the adapter reports Pi's sign-ins on the host by kind, provider and path, never by value. |
| <a id="pi42"></a>[PI42](README.md#pi42) | The Sign-In Says How It Is Answered | `pi.yaml` declares `login.flow: device-code`, so a person granting Pi's GitHub Copilot sign-in from a host is told to confirm the code on GitHub's device page; tested in sprint 6m: passed. |
| <a id="pi21"></a>[PI21](README.md#pi21) | A Login Inside A Brokered Task Fails | Finished 2026-10-27: Pi's directory is `/var/lib/pi/agent`, outside the home, with `auth.json` and `mcp-auth.json` root's and read-only, so a `/login` in a task leaves no key where Pi reads one and the agent cannot write, remove or replace either store. |
| <a id="pi31"></a>[PI31](README.md#pi31) | Take The Legs From A Run That Proved The Same Tree | A Build on `main` asks `porelo-release proven-legs` whether a Build or an update run already proved the pushed tree on both legs within a day and takes those legs instead of renting again, while a tag always runs its own. |
| <a id="pi34"></a>[PI34](README.md#pi34) | Publish Without A Downloaded Client | Both Publish steps and the Artifactory smoke workflow talk to Artifactory through `.github/scripts/artifactory.sh` with `curl`, with no JFrog CLI or action left under `.github/`. |
| <a id="pi35"></a>[PI35](README.md#pi35) | The Tree Builds Without A Container | `build-pi-tree.sh` builds Pi's tree either in the pinned Node container or, with `PI_TREE_DIRECT=1` in `porelo.yml`, directly with the pinned Node under `umask 022`, producing the same tree byte for byte where no nested container can start. |
| <a id="pi32"></a>[PI32](README.md#pi32) | Move To Pi 1 | The package ships Pi 1.3.0, dropping the 13 advisories in Pi 0.85.0's dependencies, with each measured claim in `pi.yaml` naming the version it was checked on. |
| <a id="pi27"></a>[PI27](README.md#pi27) | Declare The App Copilot Is Granted Through | `pi.yaml` declares the GitHub App `Iv1.b507a08c87ecfe98` that Pi signs in with for `porelo vault authorize github-copilot`, `BuiltTreeCheck` holds the shipped tree to that id, and `doc/index.md` states whose app it is cited as and where a grant is withdrawn. |
| <a id="pi30"></a>[PI30](README.md#pi30) | Reformat To The Shared Style | The code is in the shared style, google-java-format in its AOSP variant with YAML and Gherkin at four spaces; `validate` refuses code that is not, and `git blame` with `.git-blame-ignore-revs` skips the reformatting. |
| <a id="pi28"></a>[PI28](README.md#pi28) | Read The Code Changed Since The Last Skills Review | The code changed since 2026-09-27 read against seven skills, each finding fixed, with a test where it could do harm: no terminal control character reaches a log, and no checkout keeps the job's token. |
| <a id="pi29"></a>[PI29](README.md#pi29) | Rename The Adapter Porelo | The repository, its artifact, its packages, its Java packages and its documents say Porelo; a documents test keeps the old name to the released part of `CHANGELOG.md`. |

## What lives here and what in Porelo

An agent's work lives in its own repository, so the requirements for Pi are issues here. The
reasoning that outlives them is in [`doc/decisions.md`](../doc/decisions.md).

The candidate agents without a repository wait in `porelo-project` until a repository builds them.

## Where the rest of the open work lives

Not everything open is an issue. Accepted risks - exposures that are known, deliberate and not
being removed - are in [`doc/decisions.md`](../doc/decisions.md) with what would change the
answer. A finding that has been answered is neither: it is in the code, with its reasoning.
