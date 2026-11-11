# Issues

The open tasks of `porelo-buildtools`, grouped by state; the order within a group is the priority. The rules for an
issue, and who sets which state, are in [AGENTS.md](../AGENTS.md). *Open questions*, in *Analyzed* only, counts what
waits on the operator: the items under an issue's own *Open questions*. Its prefix is `BT`. A group with nothing in it is
left out.

## Review

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="bt46"></a>[BT46](BT46-The-Plan-Pages-Read-Stories.md) | M | - | A story's data in a JSON head of its file, checked by a schema held here, `stories/README.md` generated from the heads and checked equal; `plan render` and `plan check` read a release as stories in place of sprints, each story's state its least advanced issue's. |
| <a id="bt53"></a>[BT53](BT53-Check-Shared-Holds-The-Pinned-Rule.md) | XS | - | `check-shared` refuses a repository's `pinned.sh` that differs from porelo-buildtools' own, named in the call or not. |
| <a id="bt52"></a>[BT52](BT52-A-Pinned-Download-From-Github-Goes-With-The-Jobs-Token.md) | S | - | A pinned download from github.com goes with the job's token, to there alone; the release reader asks again by the pinned rule. |
| <a id="bt23"></a>[BT23](BT23-One-Upload-Helper-For-Every-Publish.md) | S | - | One upload helper for Artifactory, published here and taken by digest in every Publish job, in place of the seven copies of `.github/scripts/artifactory.sh` (porelo, porelo-pi, porelo-omp, porelo-claude-code, porelo-codex, porelo-agy, porelo-message-matrix) and the own curl calls of porelo-message-sluice, porelo-intellij, porelo-frontend and porelo-adapters. |
| <a id="bt51"></a>[BT51](BT51-The-Pinned-Rule-Keeps-A-Deadline.md) | XS | - | A pinned download starts no attempt later than 15 minutes after its first, the attempts' own time counted with the pauses, in Java and in `pinned.sh` alike. |
| <a id="bt49"></a>[BT49](BT49-Pinned-Downloads-Cached-And-Tried-Again-By-One-Rule.md) | S | - | Every pinned download of a Build is cached by its digest and checked again after a hit, and tried again by one rule (15 min of growing pauses) when it must be fetched; a 4xx or a wrong digest fails at once. |
| <a id="bt47"></a>[BT47](BT47-Check-Commits-Takes-Porelos-Own-Trailers.md) | XS | - | `check-commits` takes `Porelo-Change-Id` and `Porelo-Signs-Anew`, each in its own form, which Porelo writes on its own commits; due before Porelo's next commit is pushed. |
| <a id="bt45"></a>[BT45](BT45-Each-Issue-Counts-Its-Stages.md) | M | - | `porelo-release stage begin` and `end` record each stage of an issue with its time and tokens in `stats/<issue>.jsonl`, `check-commits` refuses an open stage, and `stage median` reads the estimate. |
| <a id="bt43"></a>[BT43](BT43-The-JDK-Download-Tries-Again.md) | XS | - | `porelo-machines jdk` tries the download of the pinned GraalVM again on a 5xx or a failed connection, five attempts with a growing wait; a 4xx or a wrong digest fails at once. |
| <a id="bt39"></a>[BT39](BT39-Snapshots-Resolved-With-A-Read-Only-Token.md) | M | - | Builds resolve the forge's snapshots with `PORELO_PACKAGES_READ_TOKEN`, only the deploying step holds the publisher's token, and a missing `MAVEN_SNAPSHOTS_URL` fails on any forge but github.com. |
| <a id="bt42"></a>[BT42](BT42-Snapshots-From-Central-And-What-The-Host-Built.md) | M | - | A build on a host's forge reads snapshots from the project's central source and the host's registry, newest first, with a private group's names kept from the central source. |
| <a id="bt40"></a>[BT40](BT40-Work-May-Wait-On-Work-Begun.md) | S | - | `plan check` lets work wait for an issue that is Implementing, Rework, Review or Finished, and refuses only a wait for one not yet begun or Rejected. |
| <a id="bt25"></a>[BT25](BT25-Machines-Asked-For-By-Kind.md) | L | - | `Spec` and `Main` ask for a machine by kind instead of a provider's server types, the setup chooses the local pool or the cloud provider, and a check in `porelo-buildtools` refuses a provider's name outside its implementation; the old call keeps working, routed onto the new one, until every caller has moved. |
| <a id="bt24"></a>[BT24](BT24-An-Account-Rented-From-The-Local-Pool.md) | M | - | `porelo-machines` rents one run account of the host's local VM pool per run from the file porelo-host's `vm_pool` writes, waits or refuses beyond the pool's size, and resets the account when it is given back. |
| <a id="bt38"></a>[BT38](BT38-One-Directory-Per-Release-Plan.md) | S | - | A release plan is `plan/<release>/plan.yml`, its page `README.md` and its progress image beside it, so the forge shows the page on opening the directory; the old layout is not read. |
| <a id="bt36"></a>[BT36](BT36-A-Forge-Pull-Request-From-The-Same-Repository-Counts-As-Inside.md) | S | - | A pull request on the host's forge from a branch of the same repository is checked as one from inside, told apart by `github.server_url`, since Forgejo sends no `author_association`; GitHub keeps its rule. |

## Implementing

| # | Size | Blocked by | What it covers |
|---|---|---|---|

## Backlog

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="bt48"></a>[BT48](BT48-Snapshots-Filtered-In-The-Agent-Repositories.md) | S | - | The five agent repositories read Sonatype's snapshots only for the groups in `.mvn/rrf/`, in the shape `check-snapshot-filter` holds, and their Build runs it; built by Agent Smith there. |
| <a id="bt01"></a>[BT01](BT01-A-Release-Leg-Installs-The-Release.md) | S | - | A release leg installs Porelo from the channel the run publishes to, and `acceptance --candidate` needs no package name. |
| <a id="bt03"></a>[BT03](BT03-A-Rebuilt-Snapshot-Removes-Its-Predecessors.md) | S | - | A new CI snapshot, once available, deletes the older `porelo=ci` snapshots of its `os`, keeping one before it; nothing else is touched, each deletion said. |
| <a id="bt37"></a>[BT37](BT37-The-Buildtools-Agent-Works-In-A-Porelo-Task.md) | S | - | porelo-buildtools' agent, `Agent Buildtools`, in a Porelo task on the move's host: first `machines.yml` and the scheduled `check-pushes` held to GitHub, then the trial move with Forgejo's build publishing `main`'s snapshots to its registry. |

## Analyzed

| # | Size | Blocked by | Open questions | What it covers |
|---|---|---|---|---|
| <a id="bt54"></a>[BT54](BT54-The-Stories-Index-As-A-Library.md) | L | - | 0 | The stories index, its heads and the issue index reading become a small published library, native-image friendly and opening no connection unless given one, which `porelo-release` and an adapter both call. |
| <a id="bt55"></a>[BT55](BT55-Stories-Read-Their-Workflow-From-A-File.md) | L | - | 0 | One `stories/workflow.yml` in porelo-project gives every state, its board order and phases; stories and every repository's issue index check against it, a story's state is computed from its issues, and a project without the file is refused. |

## Accepted

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="bt41"></a>[BT41](BT41-Mutation-Testing-With-PIT.md) | - | - | A `pit` profile run by hand, PIT and its JUnit 5 plugin pinned, on a VM where tests call real tools, and a report sorting survivors into real gaps and harmless ones. |
| <a id="bt30"></a>[BT30](BT30-Check-Actions-Reads-Workflows-As-YAML.md) | - | - | `check-actions` judges permissions, pins and `persist-credentials` on the parsed YAML, so a quoted or flow-style form meets the same refusals as the plain one. |
| <a id="bt09"></a>[BT09](BT09-A-Limit-Wait-Names-The-Limit.md) | - | - | The waiting line at `resource_limit_exceeded` says which limit the API names (dedicated vCPUs, shared vCPUs, primary IPs), not only the server limit. |

## Idea

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="bt50"></a>[BT50](BT50-A-Pin-Names-Fallback-Sources.md) | - | - | A pinned download names several sources, each checked against the same sha256, asked in order when the first cannot answer. |
| <a id="bt44"></a>[BT44](BT44-Old-Snapshot-Packages-Deleted-Daily.md) | M | - | A daily workflow deletes snapshot package files older than four weeks from `porelo-dist-deb` and `porelo-dist-rpm`, keeping the newest three versions of each package name and never touching `releases`; a dry run's list is read before the schedule goes live. |
| <a id="bt33"></a>[BT33](BT33-Machines-From-Providers-Behind-One-Interface.md) | - | - | One interface for asking for a machine, each source a provider behind it: clouds provided through OpenTofu, the host's VM pool and a fixed server lent; the providers per kind as one variable on both forges with different values. |
| <a id="bt18"></a>[BT18](BT18-A-Runner-Named-By-Its-Version.md) | - | - | `check-actions` refuses `runs-on: ubuntu-latest` and the other moving labels, naming file and job; a runner named by its version passes. |

## Finished

| # | Title | What was built |
|---|---|---|
| <a id="bt17"></a>BT17 | Commit Messages Checked Before And On A Push | `porelo-release check-commits` holds each commit of a range to the shared rule on commit messages; every repository runs it before a push, on its pushed range, on a pull request and on main's last day, from one template. |
| <a id="bt34"></a>BT34 | The Own Update Workflow's Pull Request Counts As Inside | A pull request the repository's own workflow opens as `github-actions[bot]` from a branch of the same repository is checked as from inside, with no sign-off asked. |
| <a id="bt35"></a>BT35 | Main Read By Its Pushes, Not Its Commit Dates | `porelo-release check-pushes` reads the day's pushes to `main` from the repository's activity and holds each by its own range, so an old commit in a push the Build skips is checked too; the scheduled step calls it, and each repository takes it from the template. |
| <a id="bt13"></a>BT13 | Action Moves Merged Without A Build | A push of only `.github/` starts no Build, and an action move is merged by hand on a light pull-request check that rents nothing and is not required; no auto-merge. |
| <a id="bt10"></a>BT10 | A Leg Takes No IPv6 Address | Every server the tools rent is created with an IPv4 address and no IPv6 one, so it holds one primary IP; measured on the project's primary IPs and green in every leg kind. |
| <a id="bt32"></a>BT32 | Snapshots On The Forge's Own Registry | The same workflows resolve and deploy snapshots on Sonatype on GitHub and on the forge's Maven registry on Forgejo, chosen by variables and secrets alone, with release tags published only to Sonatype. |
| <a id="bt28"></a>BT28 | A Release's Publish Job May Write Its Contents | `porelo-release check-actions` lets the job `publish` of `release.yml` write `contents`, so porelo-host's release can attach its tarball and digest, and holds that job to runner commands alone: no fetched action, image or shell template, only its listed env names, and expressions only in env values and if. |
| <a id="bt31"></a>BT31 | A Function Names The Parents It Covers | A plan function names under `covers:` the parent issues it covers with no work of their own; both pages show them as one line with their state, outside the sprint's bar and counts, and `plan check` refuses a parent missing from its index, named twice, or behind its parts. |
| <a id="bt29"></a>BT29 | The Plan Page Tells Ready From Undecided | Nine colour groups in the states' flow order, Backlog, Estimated and Idea each its own; the progress image named `progress-<8 hex of its SHA-256>.svg`, the release's older ones removed, so a preview and GitHub show the new one. |
| <a id="bt15"></a>BT15 | Legs Taken From A Proven Run | `porelo-release proven-legs` lets a Build on `main` take its legs from a run of this repository that proved the same tree, so a squash merge rents no machine twice. |
| <a id="bt04"></a>BT04 | Issue Numbers Link Their Index Row | `check-citations` holds every issue number in `issues/` to a link to its index row, in its own repository or another's, and refuses a link to a row that is not there. |
| <a id="bt22"></a>BT22 | A Pin With Several Artifacts | `check-pin` and `add-fetched-cli` take `--pin <name>` and check and record one artifact of a CLI downloaded as several by the digest its own pin holds. |
| <a id="bt21"></a>BT21 | Jackson Without Its Known Advisories | porelo-release reads its YAML and XML through Jackson 2.22.3, past the advisories published against the versions before. |
| <a id="bt20"></a>BT20 | Bills Read With A Current CycloneDX Library | porelo-release reads and writes bills with `cyclonedx-core-java` 11.0.1 and `commons-lang3` 3.18.0, past their known advisories. |
| <a id="bt16"></a>BT16 | A Snapshot Built Only From New Inputs | A push builds a system's snapshot only when the newest one's `inputs` label, a hash of what it is made from, differs; a dispatch always builds. |
| <a id="bt14"></a>BT14 | The Newest Release That Has Stood | A release tool takes the newest release at least `porelo.release.min-age` old, even where newer, younger ones exist, so a pin moves for an upstream that releases daily. |
| <a id="bt12"></a>BT12 | A Rented Machine Carries Its Expiry | Every lease labels its server `porelo-expires` from its job's time limit, and `sweep` deletes only servers past it or carrying none. |
| <a id="bt11"></a>BT11 | A Rented Machine Answers With Its Own Key | A rented machine gets a host key made per run in its `user_data`, pinned by every connection to it; a local VM's any key is an accepted risk. |
| <a id="bt27"></a>BT27 | The Release Plan Page Drawn From Templates | `plan render` draws the HTML page from precompiled jte templates and the Markdown page like it, with one self-contained progress image, sprints in boxes and the inputs' latest commit time as *Stand*; `plan check` compares both with a fresh render. |
| <a id="bt26"></a>BT26 | A Release Planned In Sprints Of Tested Functions | `porelo-release plan render` and `plan check` read a release's plan file of sprints and tested functions against every repository's issue index, write its page and refuse five faults, naming the issue or the function. |
| <a id="bt19"></a>BT19 | A Component Told Apart By Its Group | `add-component` matches a recorded component on group and name, so npm's `@types/node` and the Node runtime stand side by side in a tree's bill. |
| <a id="bt05"></a>BT05 | The Code Read Against The Skills It Names | Every module read against the seven skills `AGENTS.md` names, each finding fixed with a test seen red or declined, and its three questions decided. |
| <a id="bt02"></a>BT02 | Check Actions Reads Permissions | `check-actions` reads `permissions:`: one at the top of every workflow, read or nothing, `write` only in a job its list names, anything else refused by name. |
| <a id="bt07"></a>BT07 | Renamed Porelo Here | The tools are published as `org.fuin.porelo` `0.5.0`, and what they rent, label and install is named Porelo, the CI snapshots labelled `porelo=ci`; a documents test refuses the old word. |
| <a id="bt08"></a>BT08 | Parent Renamed Porelo Parent | `org.fuin.porelo:porelo-parent` `0.2.0`, naming the tools as `org.fuin.porelo` `0.5.0`. |

## Rejected

| # | Title | Why |
|---|---|---|
| <a id="bt06"></a>BT06 | The Gathered Push Refuses A Moved Central Forge | Moved to porelo-host, which installs the command on the host, where there is no Java. |
