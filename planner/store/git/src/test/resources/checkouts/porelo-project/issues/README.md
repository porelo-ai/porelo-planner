# Project requirements

**What belongs here.** A requirement belongs in this repository while it is about the project rather
than one of its repositories: the shape of the product, a decision that spans several repositories,
or something still too unclear to say which repository would build it; and the candidate agents
(`A`) that have no repository yet.

**A requirement leaves.** Once it is clear which repository does the work, it moves there as an
issue, a handover rather than a copy.

Within each group, ordered by what to do next; the number is identity, not sequence.

**How Porelo measures up against security guidelines** is in [security-guidelines.md](security-guidelines.md); every gap to be closed links its issue there.

**How Porelo compares with other tools that isolate or run coding agents** is in [feature-comparison.md](feature-comparison.md); every planned feature links its issue there.

**The stories, each a function the person tests and the issues it needs, by state and priority,** are in [stories/](../stories/README.md).

## Review

| # | Size | Blocked by | What it covers |
|---|---|---|---|

## Implementing

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="pj33"></a>[PJ33](PJ33-A-Plugin-May-Carry-Its-Own-License.md) | L | — | An agent, build reader, transport or client may carry its own license: the interface libraries (`porelo-agent-api`, `porelo-build-api`, `porelo-wire`, the varlink definitions) under Apache-2.0, Porelo itself GPL, the boundary written down. | — |
| <a id="pj32"></a>[PJ32](PJ32-A-Vulnerability-Has-A-Way-In.md) | M | — | Whoever finds a vulnerability reports it privately through a `SECURITY.md` in every repository and one page in `doc/`; a fix gets a GitHub advisory and a changelog line. |
| <a id="pj27"></a>[PJ27](PJ27-A-Contributor-Knows-The-Rules.md) | L | — | One guide for contributors in `porelo-project`'s `doc/`, linked from every repository's README and `CONTRIBUTING.md`: how the work is organised, how a change is made, what it must satisfy. |
| <a id="pj14"></a>[PJ14](PJ14-Work-Starts-Without-A-Project.md) | M | — | Work starts in a checked-out or picked repository without a project, in the project `default`; left: refusals naming the way for a transport or a peer, a `project.yml` named `default`, and the move to a followed project measured. |

## Backlog

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="pj107"></a>[PJ107](PJ107-The-Secrets-File-Keeps-Changes-From-KeePassXC-And-Porelo.md) | L | — | Changes made to the secrets file in KeePassXC and by Porelo at the same time are both kept, and the wizard asks how the person works. |
| <a id="pj106"></a>[PJ106](PJ106-Each-Host-Sees-Only-The-Drive-Areas-Chosen-For-It.md) | M | — | Each host sees only the drive areas the person chose for it, and OneDrive is mounted as Tresorit is. |
| <a id="pj74"></a>[PJ74](PJ74-Forge-And-Build-Server-Clients-Know-Nothing-Of-Porelo.md) | — | — | The calls to forges' and build servers' APIs in a repository of their own, `porelo-forge-clients`, one module per forge, LGPL, knowing nothing of Porelo; `porelo-adapters` translates what they return into Porelo's contracts and SARIF; owned by Agent Gorge; started in 0.8 with GitHub and Forgejo. |
| <a id="pj75"></a>[PJ75](PJ75-A-Task-Fetches-A-Snapshot-From-Its-Hosts-Forge.md) | M + S | — | A task on a host with a local forge fetches a snapshot from the forge's registry by its declared name without a credential put into the project, and still reaches nothing else on the host; the private organization refuses it today with `401`; the shared building blocks' organization is public on the host, read by everyone there without a token. |
| <a id="pj46"></a>[PJ46](PJ46-Document-Tests-Run-Only-Where-The-Agent-Works.md) | M | — | The tests that read only issues and documents run on the agent's machine and in the daily Shared rules check, never in a Build: left out by the build tool's own switch, and naming no single issue, so setting an issue Finished touches no test. |

## Analyzed

| # | Size | Blocked by | What it covers | Open questions |
|---|---|---|---|---|
| <a id="pj96"></a>[PJ96](PJ96-What-A-Host-Builds-Is-Used-On-The-Host-First.md) | L | — | What a host's own builds make - snapshots, skill packages, installable packages - is used by the other tasks on that host at once, reached on the host, not through the central repositories; the central build stays the only way out. | 1 |
| <a id="pj58"></a>[PJ58](PJ58-The-Coordinator-Works-In-One-Repository-Of-Them-All.md) | L | — | The coordinator reads every repository in `porelo-all` on the local Forgejo and changes only `porelo-project`: an incremental rebuild of every `main` by `git subtree`, a split of `porelo-project/` back as a pull request in `porelo-project`, a change elsewhere refused by name; porelo-host makes the repository and its two identities. | 2 |
| <a id="pj38"></a>[PJ38](PJ38-An-Estimate-Learns-From-The-Tokens-Spent.md) | M + S | — | The new tokens of build and rework, counted per task tagged with its issue and phase, are written beside an issue's estimated size before it is Finished; a velocity per size is kept, and a new estimate is compared with finished issues of its size; porelo keeps a task's start values in its usage record. | 2 |
| <a id="pj09"></a>[PJ09](PJ09-A-Gates-Green-Says-Nothing-Until-Its-Reference-Set-Can-Change.md) | L | — | Every gate names its reference set; one that is empty, cannot change or is read by nothing is not counted as coverage. | — |
| <a id="pj16"></a>[PJ16](PJ16-An-Organisation-Decides-Which-Providers-Its-Machines-Use.md) | L | — | A root-owned machine policy names the providers tasks may use, and narrows follows, prompts and the `online` class; a developer cannot widen it. | — |
| <a id="pj03"></a>[PJ03](PJ03-An-Attachment-May-Be-Permitted.md) | XL | — | Whether a message may carry more than text becomes an operator's decision, per direction, project and peer, refused by default. | — |
| <a id="a09"></a>[A09](A09-Agent-Grok-Build.md) | L | — | Grok Build as a packaged agent: a candidate, brokering unverified. | — |
| <a id="a10"></a>[A10](A10-Agent-OpenCode.md) | L | — | OpenCode as a packaged agent: a base URL configurable per provider. | — |
| <a id="pj25"></a>[PJ25](PJ25-McPorelo-Apple-Containers.md) | XL | — | McPorelo: the same guarantees on Apple Containers, driven by one client through the daemon's contract. | 4 |


## Accepted

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="pj105"></a>[PJ105](PJ105-An-Issue-Carries-Its-Data-In-A-JSON-Head.md) | — | [PJ104](#pj104) | Every issue carries its data in a JSON head checked by one shared schema, each index is generated from the heads, and tools read the heads instead of Markdown tables. |
| <a id="pj104"></a>[PJ104](PJ104-A-Story-Groups-The-Issues-Of-One-Function-The-Person-Tests.md) | — | — | A story groups every issue of one function a user values, with the person's test; work follows each story's priority, a release is a cut of the stories tested since the last, a story moves through an issue's states, and the coordinator is started with stories. |
| <a id="pj98"></a>[PJ98](PJ98-Check-Cross-References-Of-Issues.md) | — | — | Citations between the repositories' issues and documents are checked in one place that sees every repository; no repository's build or task reads another repository's files for it. |
| <a id="pj97"></a>[PJ97](PJ97-A-Repository-Can-Be-Renamed-Without-Losing-Work.md) | — | — | A repository's key renamed in the project file, by hand or signed, in one step or two, loses nothing on a following machine or a carrying host: waiting work, tasks, mirror, gate, the judged `porelo.yml` and the host's forge; measured today as a silent removal plus addition. |
| <a id="pj91"></a>[PJ91](PJ91-A-Porelo-GitHub-App-For-The-Comfortable-Start.md) | — | — | A person starts at GitHub by installing Porelo's app and signing in from the window with a device code, with no token made by hand, no private key shipped and no token on a host; beside the token way and the manual way, the host's deploy keys staying. |
| <a id="pj80"></a>[PJ80](PJ80-Only-The-Project-File-Needs-A-Signers-Signature.md) | — | — | A follow applies a new `main` when every commit that changes the project file, or another file deciding what the machines do, is signed by a key in `signers`; other commits need no signature, so developers change the rest of the project's repository on their own and the project admin signs changes to the project file. |
| <a id="pj70"></a>[PJ70](PJ70-A-Machine-Nobody-Sits-At-Tells-Its-Person-What-Needs-Them.md) | — | — | A machine nobody sits at watches what it needs to go on - credentials and their expiry, the vault, its services, memory and disk, a rented host's running time - and tells its person what needs them, wherever they are. |
| <a id="pj57"></a>[PJ57](PJ57-A-Person-Is-Led-From-Nothing-To-Work.md) | — | — | The Porelo Wizard, in a repository of its own with an agent of its own: questions first, a plan of every step, progress kept and taken up again, hand-overs with instructions to an administrator or a forge's, and a chat beside it in the interface; the core of the usability sprint. |
| <a id="pj55"></a>[PJ55](PJ55-Porelo-Runs-On-arm64.md) | — | — | Porelo and every native package it installs built, tested and published for arm64, so a Linux VM on Apple silicon installs them, for porelo-frontend [F109](https://github.com/porelo-ai/porelo-frontend/blob/main/issues/README.md#f109): built and checked on GitHub's arm64 runners on every Build; for a release, a Mac rented for the whole test window tests every part, the interface included. |
| <a id="pj49"></a>[PJ49](PJ49-A-Test-Names-The-Capability-It-Needs.md) | — | — | A test that needs what a Porelo task does not give - a nested container, `/dev/kvm`, the kernel keyring, a desktop session - carries a tag naming that capability; the tags say only what the agent runs in its task before it pushes; the task is finished when the build its push started, which runs every test, is green; mending a test to run in a task comes first where it is cheap. |
| <a id="pj47"></a>[PJ47](PJ47-A-Known-Vulnerability-Is-Read-And-Answered.md) | — | — | Every advisory against what a repository resolves is read within a week: ships or builds, reached or not, a fix or the reason; a release holds no high one against what ships unless the operator decides. | — |
| <a id="pj34"></a>[PJ34](PJ34-Every-Change-Reaches-Main-Through-A-Pull-Request.md) | — | — | Every change reaches `main` through a pull request with green required checks, so `main` needs no bypass. |
| <a id="pj35"></a>[PJ35](PJ35-An-Agent-Orchestrates-Porelo-Machines.md) | — | — | An MCP server outside Porelo lets an agent steer tasks and machines of one or many Porelo instances, without approving work or opening a vault. |
| <a id="pj36"></a>[PJ36](PJ36-Builds-Read-From-Bitbucket-Data-Center-And-Jenkins.md) | — | — | Builds of a push read from Bitbucket Data Center and Jenkins, the build server separate from the forge. |


## Idea

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="pj103"></a>[PJ103](PJ103-The-Coordinator-Works-From-A-Task.md) | — | — | The coordinator runs in a task like every agent, mostly without a shell: it starts, follows and stops the tasks it started through an MCP endpoint Porelo offers, within limits the signed project file sets. |
| <a id="pj102"></a>[PJ102](PJ102-The-Shared-Rules-Read-True-Inside-A-Task.md) | — | — | Every rule of the shared block can be followed as written by an agent inside a task, and still outside one. |
| <a id="pj101"></a>[PJ101](PJ101-A-Tasks-Commits-Name-Their-Harness-And-Model.md) | — | — | Every commit an agent makes in a task names the harness and model that drove it, and a commit without it is refused. |
| <a id="pj100"></a>[PJ100](PJ100-A-Push-Is-Checked-For-A-Persons-Own-Terms.md) | — | — | Work leaves the gate carrying no word that names the person's own machine unless forwarded knowingly; the list of words is never public nor at rest on a host. |
| <a id="pj99"></a>[PJ99](PJ99-Every-Rule-Of-AGENTS-md-Holds-Inside-A-Task.md) | — | — | An agent in a task, holding only its own repository, can follow every rule of the shared block; what a rule needs from outside is handed in at the start, checked outside the task, or the rule is reworded. |
| <a id="pj95"></a>[PJ95](PJ95-Where-A-Person-May-Not-Add-A-Deploy-Key.md) | — | — | A person who may not add a machine's deploy key at the central forge still brings a project to a machine: Porelo hands them what to ask an administrator for, and no token comes to lie on a server. |
| <a id="pj93"></a>[PJ93](PJ93-Work-Arrives-Under-The-Branch-Names-A-Forge-Requires.md) | — | — | Wherever Porelo pushes work, online or after a gate, the branch name follows what that repository's forge accepts, and a person can set it. |
| <a id="pj92"></a>[PJ92](PJ92-Every-Forge-Call-Behind-One-Contract.md) | — | — | The window asks only Core, Core asks only an adapter, and only the adapter knows a forge, so any forge offering what Porelo needs can be used. |
| <a id="pj90"></a>[PJ90](PJ90-Several-Organizations-On-One-Machine.md) | — | [PJ87](#pj87) | One machine works with several organizations at once, each holding only its own people, projects and hosts and answered by its own identity source; a person keeps one card, filed in each organization, and the interface shows them side by side. |
| <a id="pj77"></a>[PJ77](PJ77-Agents-Compared-On-The-Same-Work.md) | — | — | Several agents with their own models build the same issue side by side, one more compares the results, and time, tokens and cost are measured; finished issues with their commit serve as references; the overview feeds which agent and model suits which work. |
| <a id="pj64"></a>[PJ64](PJ64-A-Task-Chooses-Its-Agent-And-Effort-By-Its-Work.md) | — | — | A task gets the agent, provider, model and effort its work calls for - by its phase (`build`, `check`, `rework`) and its issue's risk - on one effort scale each agent maps to its provider, with a flag, the project's rule, its default and the machine's agent in that order; a `check` task only reads and reports. |
| <a id="pj65"></a>[PJ65](PJ65-What-Is-Left-Of-Each-Providers-Limits-Is-Shown.md) | — | — | Every account the agents spend on - subscriptions by share of a window, API keys by tokens or money, a forge's AI credits, a company's budget per developer, several of a kind told apart - with its meters read by each provider's adapter or estimated from Porelo's own count, shown in one view with what is left and when it comes back. |
| <a id="pj66"></a>[PJ66](PJ66-An-Adapter-Offers-The-Services-It-Has.md) | — | — | Each adapter Porelo finds lists the services it offers - agent, build, review, model limits, transport - each by its own contract and version, answers "not supported" where a service cannot, and a project names adapter and credential per service, so a forge, a build server and a model provider are never mixed up. |
| <a id="pj67"></a>[PJ67](PJ67-A-Change-Is-Checked-Before-It-Leaves-The-Machine.md) | — | — | Work waiting at a task's gate is checked by another agent's `check` task on that very commit; its findings stay with the commit and wake the building task, which fixes and pushes again, in bounded rounds, before the person approves at the gate with every round in view. |
| <a id="pj72"></a>[PJ72](PJ72-Every-Review-Seen-As-One-Area.md) | — | — | Every issue about reviewing work - by the person at the gate, by a checking agent, by comments on a forge's pull request - listed in one place by theme, so the order and the gaps are decided for the area as a whole; Porelo reads and writes review data only as SARIF, the adapters convert to and from each forge, IntelliJ reuses what it ships. |
| <a id="pj73"></a>[PJ73](PJ73-Who-Wrote-Something-Decides-What-It-May-Do.md) | — | — | Every pull request, commit and review comment gets a source level from authenticated data (external unknown, external known, internal, agent) and its code a risk class by what it touches; the weaker decides which operations Porelo performs - delivery to an agent, suggestions, reviewers, builds, secrets, forge replies, approvals. |
| <a id="pj82"></a>[PJ82](PJ82-A-Small-Change-Is-Tried-Only-Where-It-Changed.md) | — | — | On a host with a local forge, a bugfix or small change made in a task is tried on the host's VM only in the area it changed, to shorten the runs on the local VM, and the second agent's review is limited to that area; both only show that nothing there is broken, the full test runs on the central forge; a project with only the central forge is not affected. |
| <a id="pj84"></a>[PJ84](PJ84-An-IntelliJ-Plugin-Skill-Of-Our-Own.md) | — | — | A reviewed skill in the shared skills store for building the IntelliJ plugin, stating what holds for today's IntelliJ Platform - Gradle plugin and SDK versions, descriptor, threading, services, tool windows, a headless test run - built from what the plugin's spike measured, since the skills on offer are outdated or tied to a third party. |
| <a id="pj86"></a>[PJ86](PJ86-A-Hard-Bound-On-The-Agents-A-Task-Starts.md) | — | — | Porelo itself bounds the agents a task runs at once, counting an agent that starts another copy of itself through its shell, by a process limit in the task container or a cap at the provider proxy, beside the soft switch each agent CLI offers. |
| <a id="pj56"></a>[PJ56](PJ56-A-Release-Is-Tested-Together-On-One-Host.md) | — | — | Every part's release candidate installed and tested together on one host rented for the release's test window, a VM per operating system and an account per part, the arm64 Mac of [PJ55](#pj55) beside it; the per-push legs stay. |
| <a id="pj48"></a>[PJ48](PJ48-GitHub-Does-Not-Run-Scheduled-Workflows-Reliably.md) | — | — | GitHub starts scheduled workflows late or drops them, silently, while the updates, the sweep and the daily rules check must run when nobody develops; ideas: start them from outside by `workflow_dispatch`, catch up only what is overdue, notice a missing run, and where such a timer runs. |


## Estimated

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="pj52"></a>[PJ52](PJ52-A-Repositorys-Build-Tools-Are-Recognized-And-A-Project-Proposed.md) | XL | - | Drafts proposed from what a checkout and the person's tool settings say: the tools as a `.porelo.yml` the repository never receives, the rules (egress, private repositories, memory) as a `project.yml` for a project repository of the person's own; one plugin per build tool in `porelo-ecosystems`, a native executable of its own spoken to over varlink, that never runs the project's code and reads only local caches (Maven's effective model through MIMA, offline), measured against the Porelo repositories' own `porelo.yml`; credentials named by id and offered into the Porelo-Vault, never copied. |
| <a id="a08"></a>[A08](A08-Agent-Copilot-CLI.md) | L | — | Copilot CLI as a packaged agent: brokerable by environment variable (BYOK), which is not the Copilot subscription. |
| <a id="pj15"></a>[PJ15](PJ15-A-Tool-Does-Not-Change-Behind-The-Persons-Back.md) | XL | — | An MCP server's tools are pinned when a person accepts them, a change is held until accepted again, and descriptions written to steer the agent are caught. |
| <a id="pj08"></a>[PJ08](PJ08-An-Unattended-Jobs-Refusal-Reaches-A-Person.md) | L | — | An unattended job's refusal reaches a person. |
| <a id="pj24"></a>[PJ24](PJ24-Where-The-Agent-Protocols-Touch-This.md) | M | — | For A2A, MCP, ACP and AG-UI each: adopted, answered otherwise, or refused, with the reason and what would change it. |

## Finished

| # | Title | What was built |
|---|---|---|
| <a id="pj51"></a>[PJ51](README.md#pj51) | The person's secrets kept once in a file they can open | The host's secrets kept once in secrets.kdbx with one master password given on stdin (HO37, F198, F199, tested 2026-10-28); concurrent changes and the wizard's question went to [PJ107](README.md#pj107). |
| <a id="pj50"></a>[PJ50](README.md#pj50) | A host mounts the person's drives | Setup files uploaded over ssh and the person's Tresorit drive mounted in their own account (HO36, F197, tested 2026-10-28); the drive areas per host and OneDrive went to [PJ106](README.md#pj106). |
| <a id="pj40"></a>[PJ40](README.md#pj40) | A project comes up on a server at one command | A rented host goes up and down at one command, built as F131-F141 and HO02-HO14 and run at every move; the project's store lives on in ST23 with HO07. |
| <a id="pj53"></a>[PJ53](README.md#pj53) | The agent already on the machine is the default | A start without `--agent` and `--provider` takes the agent installed and signed in on the machine, with its provider; each packaged agent reports what it finds of itself by path, never a credential's value; the choice and its source are named, and a sign-in not in the vault is offered for `vault import` (0.6 sprint 6s). |
| <a id="pj83"></a>[PJ83](README.md#pj83) | A person joins with one click | One Ed25519 key per person, cards and the organization signed in Git on the central forge, lists built from it, task keys certified per task, and SSH alone with no VPN (0.6 sprints 6m and 6q). |
| <a id="pj89"></a>[PJ89](README.md#pj89) | A project is handled apart from its machines | A project and its members are edited on the project, which names no host; a host admin puts it on a host, and the host follows the organization by itself, admitting and locking members with no step of anyone; no Join, no access file (0.6 sprint 6q). |
| <a id="pj88"></a>[PJ88](README.md#pj88) | A project created from the interface | An organization owner adds a project and names its project admin, who makes its project repository and signs its first `project.yml` on first opening it (0.6 sprint 6q). |
| <a id="pj87"></a>[PJ87](README.md#pj87) | The organization behind an interface | The registry became the organization: owners, organization admins, people, projects with their members and hosts, behind one API with the Git adapter linked into Porelo, every store held by one contract suite; `porelo org …` (0.6 sprint 6q). |
| <a id="pj81"></a>[PJ81](README.md#pj81) | A repository says whether a merge needs a review | The project file says per repository whether a merge on the host's forge needs another person's approval or the author may merge; only people merge, never an agent (0.6 sprint 6q). |
| <a id="pj79"></a>[PJ79](README.md#pj79) | An issue names nothing of another repository | No issue names another repository's issue; the rule stands in the shared block and `CheckCitations` holds it, and waits stand in `plan/after.yml` (operator, 2026-11-04). |
| <a id="pj54"></a>[PJ54](README.md#pj54) | The ChatGPT sign-in is a provider | ChatGPT as a provider, built in `porelo`: `providers/chatgpt.yaml` with `credential_headers` and a decision entry (operator, 2026-11-04). |
| <a id="pj78"></a>[PJ78](README.md#pj78) | A host is set up once, and projects are put on it | Setting up a host and putting a project on it are two acts; a host carries several projects and takes one off leaving its forge standing. [PJ87](#pj87) and [PJ89](#pj89) replaced the signed access file, `bring` and the VPN this first named (operator, 2026-11-04). |
| <a id="pj76"></a>[PJ76](README.md#pj76) | A pull request's commits checked alike on both forges | `shared-rules.yml` reads a pull request's commits from a whole checkout, with nothing fetched again. On GitHub an owner, a member and the repository's own bot count as inside; on a host's forge every pull request of the same repository does, as only write access pushes there and only a person's approval merges; a fork stays outside. The operator confirmed this rule of trust on 2026-11-04. |
| <a id="pj71"></a>[PJ71](README.md#pj71) | Who reaches a host, and by which way | Roles come from the organization, not from a parameter at the interface's start ([roles](https://github.com/porelo-ai/porelo/blob/main/doc/roles.md)), and only ssh reaches a host in normal work ([the decision](../doc/decisions.md#only-ssh-reaches-a-host-in-normal-work)) (operator, 2026-11-04). |
| <a id="pj61"></a>[PJ61](README.md#pj61) | The project porelo is online with the local forge upstream | A task's push passes its gate to `porelo/<task>` on the host's Forgejo, which builds it; the verdict and a red job's log reach the task; the gate opens the pull request, the operator approves and merges fast-forward only, and the gathered push alone reaches the central forge; offline reaches no forge. Proved on Sluice with the project `sprint5` (0.6 sprint 5, tested 2026-10-27); the project `porelo`'s own file follows with [PJ59](README.md#pj59). |
| <a id="pj69"></a>[PJ69](README.md#pj69) | A machine reaches the forge with the person's own credentials | The person's token kept in the vault and used for git and the forge's API, no deploy key per machine and repository: `porelo` [B269](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b269), `porelo-frontend` [F153](https://github.com/porelo-ai/porelo-frontend/blob/main/issues/README.md#f153) (sprint 2c). |
| <a id="pj62"></a>[PJ62](README.md#pj62) | The forge adapters live in one repository | `porelo-adapters` with the `github` and `forgejo` adapters, one contract suite both pass, and `porelo build status` asking either (sprint 3: [GH06](https://github.com/porelo-ai/porelo-adapters/blob/main/issues/README.md#gh06), [GH07](https://github.com/porelo-ai/porelo-adapters/blob/main/issues/README.md#gh07), [B253](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b253), [B256](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b256), [B275](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b275)). |
| <a id="pj63"></a>[PJ63](README.md#pj63) | A release is planned in sprints of tested functions | `plan/<release>/plan.yml` holds each release's goal, sprints, functions the operator tests and their issues; `porelo-release plan render` makes `plan/<release>/README.md` with a progress image and `plan check` refuses a faulty plan, built in porelo-buildtools. |
| <a id="pj28"></a>[PJ28](README.md#pj28) | Code from outside reaches no secret | The organisation ruleset on `main` with the admin as its only bypass, `CODEOWNERS`, fork approval, `check-actions` refusing `pull_request_target`, and a probe that shows them holding ([decision](../doc/decisions.md#code-from-outside-reaches-no-secret)). |
| <a id="pj29"></a>[PJ29](README.md#pj29) | One code style for the IDE and the build | One shared `.editorconfig` in every repository, Spotless with `google-java-format` (AOSP) in `validate` from `porelo-parent`, Gradle's Spotless in the plugin, `dart format` in the interface, and one reformatting commit per repository named in `.git-blame-ignore-revs`. |
| <a id="pj42"></a>[PJ42](README.md#pj42) | The forge and Artifactory prepared for Porelo | Every repository transferred to `porelo-ai` and renamed, the organisation's settings and tokens made again, `porelo-dist-*` and the key pair `porelo-packages` at Artifactory. |
| <a id="pj44"></a>[PJ44](README.md#pj44) | The local machine renamed Porelo | The checkouts, local rules, channel and its tools, and the VM accounts with Porelo installed afresh. |
| <a id="pj17"></a>[PJ17](README.md#pj17) | The MVP | Its shape is in [the project chapter](../doc/index.md#the-mvp); which issue belongs to a round is now each index's *Backlog*. |

## What was decided along the way

- **Messaging is not one repository's job.** The format is A2A 1.0, an external standard; what
  remains divides into the handover at the container boundary (`porelo`), what may pass (the filter)
  and the carrying (the transports).
- **An adapter's repository is an ownership question, not an architectural one.** Transports are
  discovered executables speaking a CLI, so nothing compiles against one and moving it breaks nothing.
- **No project-wide message store**: a Matrix room is one.
- **No chat client is shipped.** Any Matrix client will do, and no agent needs one.
- **Room membership is not authorization.** Matrix distributes; Porelo decides, on the signature.

## Rejected

| # | What it covered | Why |
|---|---|---|
| <a id="pj68"></a>PJ68 | Every container a Porelo part starts - task, image build, the room's homeserver, the host's forge and runner jobs - runs with limits of its own, and a pool sized from the machine sees those limits, so a part that works on a small machine also works on a large rented host. | Overtaken (operator, 2026-11-09): porelo-message-matrix, porelo and porelo-host each file their part under ST127, from this analysis. |
| <a id="pj22"></a>PJ22 | One build, run locally as on the central forge: the same workflows on a local Forgejo, its registry instead of the hand-over and the public repositories, acceptance legs on a machine rented through one interface (a local VM account or a provider's server), and a task's work built the same way, read through one reader contract by a `github` and a `forgejo` reader. | Overtaken (operator, 2026-11-09): its parts are their own issues in ST10 (BT24, BT25, HO12), ST243 ([PJ62](README.md#pj62)) and ST178 (B254). |
| <a id="pj60"></a>PJ60 | — | Overtaken (operator, 2026-11-09): each repository files its own issue under ST27; porelo-intellij did it as IJ14. |
| <a id="pj59"></a>PJ59 | Each agent moves into a Porelo task, one at a time in a fixed order, each move an issue (S) of its own that the agent works inside its new task; the channel file kept beside Matrix until the last has moved. | Overtaken (operator, 2026-11-09): story ST293 carries it with each agent's move story; the procedure is [Moving an agent](../doc/moving-an-agent.md). |
| <a id="pj45"></a>PJ45 | A short list of data that lives only on a machine - the Matrix room, the token usage records and the Maven cache to start with - copied every hour and at down by restic into the project's S3 store, encrypted on the host, and put back at up; nothing else is kept. | Overtaken (operator, 2026-11-09): story ST23 carries it with MX24, MX36, MX39, HO07 and B231; the design lives on in those issues. |
| <a id="pj39"></a>PJ39 | The agents work in Porelo tasks on one bare-metal host, owned or rented by the hour and set up by a script (at least 8 cores with 16 threads, 64 GB, one SSD of 1 TB): an `online` project whose upstream is a local Forgejo, one task per issue, phase and repository, Forgejo building and testing, the operator approving there, Matrix, what an agent learns committed to its `AGENTS.md` instead of memory, `porelo-all` for the coordinator; a plan in nine sprints after the groundwork, each ending in functions the operator tests. | Overtaken (operator, 2026-11-09): the stories carry the plan; its parts stay as their own issues. |
| <a id="pj94"></a>PJ94 | The release plans always show the issues' real states without anyone rendering them, and no build writes to `main`. | Overtaken (operator, 2026-11-09): the stories and their check replaced the release plans and their pages on 2026-11-08; the `plan-pages` branch is deleted. |
| <a id="pj37"></a>PJ37 | A machine reaches the upstream by keys of its own or by the person's own token, equal choices per project. | Overtaken (operator, 2026-11-04): machines read with a key per repository, a host forge's token is taken per command, the gathered push takes the person's token on stdin, and no token lies on a server. What remains is [PJ95](#pj95). |
