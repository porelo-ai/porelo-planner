# Issues

What is open in this repository, ordered by what to do next rather than by number. The number is
identity, not sequence.

**Grouped by state**, each group's heading naming it, as the shared rules set them out; within a group
the order is the priority. Only the operator sets Accepted, Rejected, Estimated, Backlog, Rework and
Finished; this repository's agent sets Analyzed, Implementing and Review, and proposes the rest in the
channel.

Its prefix is `HO`. *Open questions*, in *Analyzed* only, counts what waits on the operator. A group with nothing in
it is left out.

## Review

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="ho154"></a>[HO154](HO154-A-Task-Account-Pushes-Only-Its-Own-Task-Branches.md) | S | - | A task's token pushes and force-pushes only `porelo/<task>` of its own person's tasks; the host refuses any other ref from that account. |
| <a id="ho152"></a>[HO152](HO152-A-Burst-Never-Stops-A-Gate-Socket.md) | XS | - | Bug: a burst of connections never stops a gate socket for good: no trigger limit fails the forge, judge or Matrix gates, each bounds its connections, and up checks that every gate socket listens. |
| <a id="ho151"></a>[HO151](HO151-The-Pinned-Jdk-Action-Takes-The-Shared-Rule.md) | XS | - | The CI's pinned GraalVM is fetched by the shared rule: cached by its digest, tried again by `pinned.sh`, with the job's token from github.com. |
| <a id="ho150"></a>[HO150](HO150-The-Forge-Roles-JDK-Fetch-Tries-Again-By-The-Pinned-Rule.md) | XS | - | Bug: the forge role fetches the runner's JDK once, with a 10 s timeout, so one outage of GitHub's release downloads fails a host's first up; it tries again on a 5xx or no answer within 15 minutes and fails at once on anything else. |
| <a id="ho148"></a>[HO148](HO148-The-Move-Check-Finds-The-Push-By-Its-Branch-And-Names-The-Answer.md) | XS | - | Bug: the move check finds the push in the forge repository that holds its branch, fails a Build nobody reads, and names what the forge, Git or the vault answered. |
| <a id="ho147"></a>[HO147](HO147-A-Hosts-Builds-Also-Read-The-Upstream-Snapshots.md) | XS | - | Each organization the host registers holds `MAVEN_UPSTREAM_SNAPSHOTS_URL`, Sonatype's snapshots read anonymously, so a build resolves the snapshots the host's registry lacks. |
| <a id="ho146"></a>[HO146](HO146-A-Lent-Forge-Token-Leaves-The-Persons-Token.md) | S | - | Porelo's git commands on the host's forge take a lent token of their own, so the person's `<forge>.person` token in the vault, which a task's builds read, stays valid. |
| <a id="ho145"></a>[HO145](HO145-A-Host-Task-Reads-Its-Builds-From-The-Hosts-Forge.md) | S | - | A host task reads its Builds from the host's forge: the follow names `builds` in each project's file beside `fetch_from`. |
| <a id="ho142"></a>[HO142](HO142-The-Persons-Vault-Holds-Their-Forge-Token-From-The-Start.md) | S | - | Bug: the person's vault holds `<forge>.person` from the start, made the one way `porelo-host-push` makes it. |
| <a id="ho144"></a>[HO144](HO144-The-Forge-Gates-Refusal-Names-Its-Address.md) | XS | - | Bug: the forge gate's 403 for a foreign `Host` names the address that works. |
| <a id="ho141"></a>[HO141](HO141-The-Move-Check-Reads-Push-And-Pull-Request-From-The-Forge.md) | S | - | Bug: the move check reads the push and the pull request from the host's forge, and names the Build not configured without `builds:`. |
| <a id="ho143"></a>[HO143](HO143-Work-Ahead-Of-Central-Is-Said-And-Holds-The-Give-Back.md) | S | - | Bug: `org status` says each taken repository's state against central, and `down` refuses while work is not at central, unless given back anyway. |
| <a id="ho139"></a>[HO139](HO139-The-Host-Reads-Central-Alone.md) | XS | - | Bug: the host reads a repository of the project file by its name and `central` alone; the project file names no host. |
| <a id="ho133"></a>[HO133](HO133-A-Host-Carries-Single-Repositories-Of-A-Project.md) | M | - | Single repositories of a project carried: the follow takes only those the host's card names, one taken off the forge with its read key at the central forge given the token, status per repository. |
| <a id="ho138"></a>[HO138](HO138-The-Move-Check-Reads-Each-Line-Where-It-Stands.md) | S | - | Bug: the move check reads the push from the forge's branch the task names, not the build reader, and the conversation from the task, not the peers list. |
| <a id="ho137"></a>[HO137](HO137-A-Tasks-Account-Opens-Its-Pull-Request.md) | S | - | The task's token opens its pull request `porelo/<task>` → `main` on the host's forge, and the person's approval counts, measured in the follow act. |
| <a id="ho136"></a>[HO136](HO136-A-Persons-Daemon-Takes-The-Group-It-Joins-Later.md) | S | - | Bug: a person who joins `porelo-people` after admission gets a `porelod` with that group, its user manager restarted where no task runs. |
| <a id="ho135"></a>[HO135](HO135-The-Hosts-Copy-Follows-A-File-Signed-Anew.md) | S | - | Bug: the host's copy of a project's own repository advances to a commit signing `project.yml` anew, not only to its last change, and the follow records what `main` reached. |
| <a id="ho134"></a>[HO134](HO134-A-Projects-Why-Gathers-Its-Repositories-Reasons.md) | XS | - | Bug: a project's `why` gathers like reasons of its repositories in one line, none for repositories without a central address; the detail stays in `repositories[]`. |
| <a id="ho132"></a>[HO132](HO132-A-Move-Rehearsed-On-A-Local-VM.md) | S | - | `build/rehearse-move.sh` makes a throwaway VM, runs `up` with the forge and no pool, opens the room tunnel and takes it all down again, so a move is rehearsed before a host is rented. |
| <a id="ho131"></a>[HO131](HO131-A-Task-Reads-Its-Projects-Snapshots.md) | S | - | A task's token also reads its organization's packages, `read:package`, so a task resolves its project's snapshots from the host's forge; it writes none. |
| <a id="ho129"></a>[HO129](HO129-The-Web-Gate-Serves-Its-Own-Site-Alone.md) | S | - | The web gate refuses a `Host` other than the forge's own site, and on a request that changes something a foreign `Origin` or a cross-site `Sec-Fetch-Site`, so another page in the browser cannot act as the person. |
| <a id="ho126"></a>[HO126](HO126-A-Reverted-Pool-VM-Keeps-The-Snapshots-Clock.md) | - | - | A pool VM reverted to its live snapshot keeps that clock, and `apt` refuses what was published since; the host's time is set by the guest agent. |
| <a id="ho116"></a>[HO116](HO116-The-Firewall-Left-By-Down.md) | - | - | `down` leaves the firewall's file and, by reading, its unit: taken off, or said why it stays |
| <a id="ho106"></a>[HO106](HO106-A-Refused-Root-Key-File-Says-Why.md) | - | - | `up` refusing root's key file says how many keys it found and what to do, not only `non-zero return code` |
| <a id="ho119"></a>[HO119](HO119-Which-Server-For-What.md) | - | [HO120](#ho120) | One page: which server for what, with and without Forgejo, each number measured or marked not, and the rule for parallel jobs. |
| <a id="ho120"></a>[HO120](HO120-A-Measuring-Run-For-Any-Machine.md) | - | - | One command that measures a given machine, again on any hardware, and writes one result file without secrets for the page's tables. |
| <a id="ho118"></a>[HO118](HO118-KVM-Only-On-A-Host-That-Asks.md) | - | - | KVM and libvirt only on a host whose `porelo_host_kvm` says yes, off by default, and the CPU checked only there. |
| <a id="ho123"></a>[HO123](HO123-A-Socket-Forward-Reaches-Past-The-Agent-Rule.md) | - | - | Say what `AllowAgentForwarding no` stops, as a person's socket forward reaches past it. |
| <a id="ho61"></a>[HO61](HO61-A-Purged-Persons-Matrix-Account-Taken-Off.md) | S | - | A purged person's account on the host's own Matrix homeserver is taken off or named, not left without a word when the transport refused to clear it. |
| <a id="ho113"></a>[HO113](HO113-A-Renamed-Branch-Rule-The-Follow-Does-Not-See.md) | S | - | The follow holds a branch rule to the signed values whatever the rule on `main` is called |
| <a id="ho52"></a>[HO52](HO52-Snapshots-Resolved-With-A-Read-Only-Token.md) | M | - | Each project organization holds a `read:package` token as `PORELO_PACKAGES_READ_TOKEN` and its own `MAVEN_SNAPSHOTS_URL`, so builds resolve snapshots without the publisher's token. |
| <a id="ho14"></a>[HO14](HO14-The-Collections-Python-Under-Ansible-Test-Sanity.md) | S | - | `ansible-test sanity` on the whole collection in the Build, offline, its requirements taken by hash. |
| <a id="ho12"></a>[HO12](HO12-The-VM-Pool.md) | L | - | Role `vm_pool`: VMs with run accounts `porelo-machines` rents from, made by the same code as the test VM, deleted by name at `down`. |
| <a id="ho81"></a>[HO81](HO81-The-Gates-Route-Match-Holds-For-Every-Decoded-Byte.md) | XS | - | No decoded byte takes a refused route past the forge gate's match, and `web.sock` challenges a client that waits for one. |
| <a id="ho83"></a>[HO83](HO83-The-Persons-Token-Kept-Where-Each-Tool-Reads-It.md) | S | - | The vault's person token stays the one in force after every remake; each task-start and token-handling check is tested by its absence. |
| <a id="ho85"></a>[HO85](HO85-The-Push-Judge-Armed-Only-Where-It-Holds.md) | S | - | A repository is armed only where the judge's hook holds, a removal takes the arrivals along, and each verdict is recorded only whole. |
| <a id="ho124"></a>[HO124](HO124-A-Login-Closed-At-Once-On-A-Host.md) | - | - | A login closed at once on a host, by reading sshd's own per-source penalty: reproduce it on the test VM, and say so. |
| <a id="ho121"></a>[HO121](HO121-One-Check-Before-Any-Installation.md) | - | [HO118](#ho118) | One read-only check before any installation, the same code `up` runs first, every finding at once with its value, what was asked and what to do. |
| <a id="ho122"></a>[HO122](HO122-A-Project-Says-What-The-Follow-Took.md) | - | - | `org status` names what the follow took of a project and why not, a row with no `central` included. |

## Implementing

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="ho158"></a>[HO158](HO158-A-Move-Rehearsed-On-A-Rented-Server.md) | L | - | The agents run a whole move on a server the operator rented, against a test organization, from `build/rehearse-move.sh`: check, up, vault, follow, forge, pool where KVM is, judge, push and pull, host check, down and read keys, a report per step, and one request to delete the server. |

## Backlog

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="ho07"></a>[HO07](HO07-The-Projects-Store-Restored-And-Kept.md) | L | - | Role `store`: restic pinned, each entry's own copy and restore command, an hourly timer of root's, a last copy at `down`, the restore before anything reads it. |
| <a id="ho125"></a>[HO125](HO125-What-A-Task-And-A-Job-Take.md) | - | [HO127](#ho127) | The measuring run also reads a task, an agent's fixed start and Maven jobs, so a size can rest on what work takes: the tool for one measuring issue in Porelo. |
| <a id="ho127"></a>[HO127](HO127-The-Half-For-Jobs-Is-A-Setting.md) | S | - | The share of the host for jobs and the VM pool becomes a setting, today's half its default, `up`'s refusal naming it. |
| <a id="ho153"></a>[HO153](HO153-The-Web-Gate-Keeps-A-Browsers-Connection.md) | - | - | Stopped by the operator, waits on the porelo-host CLI: the forge's web gate keeps a browser's connection open for several requests, each checked as the first, so a page starts few gate processes. |
| <a id="ho156"></a>[HO156](HO156-The-Collection-Installs-The-Hosts-Own-Program.md) | L | - | The host runs its admin work, gates, judge and pool through the installed `porelo-host` at the pinned version; the collection's scripts go, but for the stated exceptions. Blocked until porelo publishes the package. |
| <a id="ho157"></a>[HO157](HO157-Molecule-And-The-Rehearsal-Run-On-The-Package.md) | M | [HO156](#ho156) | Molecule and the move rehearsal install the local `porelo-host` package and check its version; `porelo-vm` moves to `build/`. |

## Analyzed

| # | Size | Blocked by | Open questions | What it covers |
|---|---|---|---|---|
| <a id="ho40"></a>[HO40](HO40-The-Build-Runs-On-The-Hosts-Forge-As-On-GitHub.md) | M | - | 1 | The move's builds on the host's forge: `build.yml` green on the host's Forgejo and on GitHub unchanged, with task branches built, the tools without `podman run`, snapshots by variable and Molecule on a machine with KVM. |
| <a id="ho39"></a>[HO39](HO39-The-Hosts-Agent-Works-In-A-Porelo-Task.md) | S | [HO40](#ho40) | 1 | porelo-host's agent, `Agent Host`, in a Porelo task on the move's host, with a way back: the image from `porelo.yml`. |

## Accepted

| # | Size | Blocked by | What it covers |
|---|---|---|---|

## Idea

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="ho149"></a>[HO149](HO149-A-Refresh-Takes-A-Token-Only-When-Something-Waits.md) | XS | - | *Refresh* takes the host admin's central token only while a project, a repository or a take-off waits for one, on a host with a forge of its own as on one without, and otherwise refuses it the same way on both. |
| <a id="ho140"></a>[HO140](HO140-A-Host-Keeps-Its-Identity-Across-Rentals.md) | - | - | A host rented again and again keeps its read keys from the person's vault, so they stay registered between rentals and only a host given up for good has them removed. |
| <a id="ho130"></a>[HO130](HO130-The-Web-Gates-Authorization-Edges.md) | - | - | `web.sock` treats an empty `Authorization` as a token and passes the cookie beside it, takes any scheme, and no test holds its refusal of a second `Authorization` or of a request target that is not a path. |
| <a id="ho128"></a>[HO128](HO128-A-Tasks-Login-Cannot-Approve-Or-Merge.md) | - | - | A task's gate login on the forge can neither approve nor merge a pull request on `main`, by the forge's own rule, not only because the task never holds its token. |
| <a id="ho117"></a>[HO117](HO117-The-Sanity-Images-Two-Pins.md) | - | - | The `pip` and `pylint` the sanity image pins, with open alerts, raised or said why they stay |
| <a id="ho115"></a>[HO115](HO115-The-Pool-VM-Not-Ready-On-GitHubs-Runner.md) | - | - | The pool's VM says ready on GitHub's runner, or the reason it cannot is known; until then GitHub does not check the pool. |
| <a id="ho114"></a>[HO114](HO114-A-Hosts-Read-Keys-Taken-Off-The-Central-Forge.md) | - | - | Taking a project off and `down` delete the host's read-only keys at the central forge, the token asked once and kept nowhere; if impossible, each is named with a link. |
| <a id="ho91"></a>[HO91](HO91-SSHs-Edges-Left-From-Its-Readings.md) | - | - | The KRL validated before sshd reads it, a root key with an options prefix refused, and a lock ordered against a concurrent follow. |
| <a id="ho92"></a>[HO92](HO92-The-Forges-Loopback-Port-Narrowed-To-Persons.md) | - | - | Only the persons and the forge account reach the forge on `127.0.0.1:<git_port>`, by an nftables rule on their uids. |
| <a id="ho97"></a>[HO97](HO97-A-Host-Reached-By-IPv6-Alone.md) | - | - | A server reached by IPv6 alone is prepared: its public interface told by the IPv6 default route, the firewall and the forge holding for it. |
| <a id="ho98"></a>[HO98](HO98-A-Projects-Central-Address-Changed.md) | - | - | A signed change of a project's central address is named, not called carried over an absent repository. |
| <a id="ho99"></a>[HO99](HO99-A-Central-Forge-On-A-Private-Authority.md) | - | - | A central forge on a private authority is reached by the fetch, the read key, the migration and the push, from one setting. |
| <a id="ho101"></a>[HO101](HO101-One-Forge-Over-SSH-And-HTTPS-On-A-Port.md) | - | - | One forge's ssh address and its https address on a port count as one forge. |
| <a id="ho107"></a>[HO107](HO107-A-Project-That-No-Token-Can-Cure.md) | - | - | A project with no usable central address has a state of its own, `waiting-for-address`, as no token can cure it |
| <a id="ho86"></a>[HO86](HO86-The-Push-Judges-Smaller-Edges.md) | - | [HO85](#ho85) | The push judge's smaller edges from its reading, gathered: links in the arming, the removal's checks, a first-push race, the null object, the answer's field and record checks pinned by tests. |
| <a id="ho78"></a>[HO78](HO78-Only-The-Gates-Sockets-Forwarded.md) | - | - | A person's SSH connection forwards only to the gates' sockets and their own return socket, and to no TCP address. |
| <a id="ho79"></a>[HO79](HO79-A-Persons-SSH-Login-Confirmed-By-A-Second-Factor.md) | - | - | A person's SSH login needs a second factor beside their key: FIDO2 touch, or TOTP through PAM. |
| <a id="ho77"></a>[HO77](HO77-Each-Job-In-A-Network-Of-Its-Own.md) | - | - | Concurrent jobs on a host cannot reach one another's network, and no host login reaches Forgejo over TCP. |
| <a id="ho58"></a>[HO58](HO58-The-Services-Podman-Kept-Small.md) | - | - | Once a day, and after a missed day, each service account's Podman loses the images and volumes no container used for a set time. |
| <a id="ho54"></a>[HO54](HO54-The-Host-Helpers-Hardened.md) | - | - | The people's SSH drop-in read first and a project registration retried after a forge timeout. |
| <a id="ho30"></a>[HO30](HO30-The-Forges-Job-Bounds-Measured.md) | - | - | A running job's container measured to carry its bounds, beyond what Molecule reads from the files the roles write. |
| <a id="ho28"></a>[HO28](HO28-The-Forges-Events-Routed-To-Each-Accounts-Porelo.md) | - | - | Optional speed-up: the forge's webhooks, reviews included, reported to one router on the host, which checks the signature and hands only a wake-up hint to the daemon of the account whose task it concerns, known from the pusher's forge account or the pull request's branch. |
| <a id="ho27"></a>[HO27](HO27-The-Forges-Jobs-Told-Where-The-Pool-Is.md) | - | - | The forge's runner tells every job where the VM pool is, so one workflow takes a pool account on the forge and rents a server on GitHub. |
| <a id="ho24"></a>[HO24](HO24-The-Setup-Runs-To-Its-End-On-The-Host.md) | - | - | `up`'s playbook runs on the host to its end whoever watches, and `porelo-host` attaches to it again when started again. |
| <a id="ho15"></a>[HO15](HO15-The-Host-On-Fedora.md) | - | - | The host on Fedora as on Ubuntu 26.04: SELinux, firewalld, `dnf` and a Molecule scenario on Fedora to measure. |
| <a id="ho90"></a>[HO90](HO90-Each-Persons-State-Named-By-Status.md) | S | - | `org status` leaves out revoked and refused logins and names a cut-off purge or a removal's window `pending`. |
| <a id="ho109"></a>[HO109](HO109-How-Long-The-Host-Has-Not-Read-Its-Organization.md) | - | - | `org status` says when the host last read its organization, as a follow that cannot fetch keeps a removed member's login with nothing said |
| <a id="ho111"></a>[HO111](HO111-The-Forges-Admin-Tokens-A-Project-Helper-Leaves.md) | - | - | A run of the project helper leaves no lasting admin token on the host's forge |

## Estimated

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="ho23"></a>[HO23](HO23-A-Machine-Rented-And-Given-Back-By-OpenTofu.md) | L | - | An administrator rents and gives back a host by one OpenTofu module per provider, its state kept encrypted with the administrator, outside the interface. |

## Finished

| # | Title | What was built |
|---|---|---|
| <a id="ho68"></a>HO68 | SSH The Host's Only Way In | A person reaches the host only over SSH on port 22, with the one key of their card; nothing listens on TCP for persons; tested in sprint 6m: passed. |
| <a id="ho69"></a>HO69 | The Forge Behind Its Gate Without TLS | The forge has no TLS and no password: a person's browser is signed in through `web.sock`, Git and the API take a token through `git.sock`; tested in sprint 6m: passed. |
| <a id="ho70"></a>HO70 | The Host's State Taken From The Registry | The host takes its project admins and projects from the signed registry state the host admin brings, verified by Porelo; tested in sprint 6m: passed. |
| <a id="ho71"></a>HO71 | A Task Recorded And Given Its Forge Token By The Host | Every forge token on the host is made there and goes with its person or task; each task's certificate is recorded at its start; tested in sprint 6m: passed. |
| <a id="ho72"></a>HO72 | The Central Forge Reached Only On A Person's Word | No token for the central forge lies on the host: migration, push and pull take the starting person's own on stdin, once; tested in sprint 6m: passed. |
| <a id="ho73"></a>HO73 | The Homeserver Reached Through Its Gate | The homeserver is reached only through the Matrix transport's gate on its socket, and Tuwunel listens on no TCP port; tested in sprint 6m: passed. |
| <a id="ho75"></a>HO75 | The Persons Taken From Their Cards | Persons, keys, signers and per-project locks come from the cards and access files, with no laptop anywhere; tested in sprint 6m: passed. |
| <a id="ho76"></a>HO76 | Each Push Judged When It Arrives | The first accepted push of a commit to the host's forge judges it against the signers and KRL in force, and records the verdict; tested in sprint 6m: passed. |
| <a id="ho80"></a>HO80 | Git And The API On The Host's Loopback With A Token | Git, the API and the pass-on reach the forge on `127.0.0.1:<git_port>` through the token-only gate; Forgejo never on TCP, the browser on `web.sock`; tested in sprint 6m: passed. |
| <a id="ho89"></a>HO89 | A Person Locked And Purged Through The Transport's Expel | Lock and purge give the Matrix transport's expel its administrator's token on stdin, and run as the person from `/`; tested in sprint 6m: passed. |
| <a id="ho50"></a>HO50 | Down Gives Back When Only A Transport Could Not Clear | `down` clears the vault and gives the server back where only a transport could not clear what it keeps, through `porelo vault clear --json`, and with `--force` in a step of its own where Porelo kept the vault for its transports; any other refusal still stops it. |
| <a id="ho37"></a>HO37 | The Host's Secrets Given On Standard Input At The Stop | The secrets the stop names given from the person's password database by a desktop terminal, the interface holding none, in one root pipeline of base64 lines, each passed straight to its user; the WireGuard key and every token off the drive. |
| <a id="ho36"></a>HO36 | The Host Reads The Files Its Setup Uploaded | The host's setup reads nothing from the drive: `machine.yml` and the project's bundle uploaded by the interface into `/etc/porelo-host/setup/`, root's and readable by the account alone, refused where the account can write them, removed at `down`. |
| <a id="ho38"></a>HO38 | The Drive Leaves The Host's Setup | Nothing of the drive on a host: the drive's role, its installer module and the stop's `drive` go, so `up` asks no login and `down` waits on no sync; a user's own drive is the interface's per-user setup. |
| <a id="ho35"></a>HO35 | The Forge Opened In The Browser Once The VPN Is Up | Once the VPN is up, the person opens the forge in their browser and signs in with their lasting password: the `/etc/hosts` line, one lasting name-constrained CA installed once whose key stays off the host's disk, and the password kept in the person's password database and set over ssh on each new forge, in no host file. |
| <a id="ho41"></a>HO41 | The Host Joins The Project's Room On The Laptop | A rented host's tasks in the project's room on the laptop during the move: the hosts line for `matrix.porelo.internal`, and the host's registration token on the stop's standard input into the file the signed project names. |
| <a id="ho34"></a>HO34 | The Forge Merges Fast-Forward Only | Each repository on the host's forge offers only the fast-forward merge, read back after it is set, so a merged pull request's own commits reach `main` unchanged with the person as author and no commit of the forge's `operator`; the central work joins on a `central/take` branch. |
| <a id="ho33"></a>HO33 | The Forge's Runner Takes Jobs Side By Side, With A Cache | The forge's runner takes four jobs at once, refused by `up` where they would not fit half the machine's CPUs and memory beside the VM pool, and serves its cache on the forge's address, so a job's `actions/cache` saves and a later run restores. |
| <a id="ho32"></a>HO32 | The Gathered Push Refuses A Moved Central Forge | `porelo-host-push`, started by the person on the host over ssh, pushes each repository's local `main` to the central forge without force, the central token asked for hidden; a moved central forge is refused, named and brought back as `central/main`. |
| <a id="ho31"></a>HO31 | The Account's Gate And Reader On The Host's Forge | The gate of Porelo's account pushes `porelo/<task>` to the host's forge by name over TLS and the `forgejo` build reader reads its verdict and logs, the token in the account's vault alone; `main` is protected so only the person's approved and merged pull request reaches it. |
| <a id="ho29"></a>HO29 | The Person's Own Web Login On The Forge | The person signs in to the host's forge through the VPN as `operator`, an owner of the organization, with a generated first password fetched over ssh from a root-only file and changed at the first sign-in. |
| <a id="ho26"></a>HO26 | Each Agent's Own Forge Account | Each agent the host's settings name gets an account `agent-<name>` on the host's forge in team `agents`, which writes the organization's code, pull requests and issues, with a token written only into a root-only file on the host. |
| <a id="ho08"></a>HO08 | The Forge With Its Registry | Where the host's settings ask for it, `up` runs Forgejo with its Actions runner and package registry in rootless podman under two accounts of their own, reached by name over TLS with the host's CA through the VPN, its repositories migrated once from the central forge with no push mirror, and `down` takes it off. |
| <a id="ho17"></a>HO17 | The Forge's CA In Porelo's Account | `up` puts the forge's CA into Porelo's account, as a marked block in `porelo/ca-certificates.pem` in the config folder for host clients and alone in `porelo/task-ca-certificates.pem` in the config folder for tasks, and `down` takes it out. |
| <a id="ho20"></a>HO20 | Every Container Of The Host Fits The Machine | The forge, its runner and each job are bounded by fixed CPU, memory and process limits, and `up` refuses an account without the `cpu`, `memory` and `pids` controllers and a VM pool larger than half the host's memory. |
| <a id="ho25"></a>HO25 | The Stop Asks Again Until It Is Done | At `up`'s stop, `porelo-host-stop` asks a mistyped drive login or vault passphrase again in the same terminal until it succeeds or the person cancels, with exits the interface reads. |
| <a id="ho21"></a>HO21 | Each Step Says What It Does And How Long | Each of the playbooks' sixteen steps carries a plain-words text and its usual duration as its own variables, which its `started` line passes on after a plan line of the run ahead, `v` staying 1. |
| <a id="ho13"></a>HO13 | A Release Carries Its Tarball And Digest | A release attaches the collection's tarball and its `SHA256SUMS`, stopping before anything is attached if the tag is not `galaxy.yml`'s plain version, so `host up` can refuse any other. |
| <a id="ho19"></a>HO19 | The Machine Installs Its Agents And Starts No Task | `up` installs every package Porelo's setup script names as an agent, and the project's transports, without starting a task or stopping for a sign-in; `down` stops every running task of the account before `vault clear`. |
| <a id="ho18"></a>HO18 | A Failed Loop Says Why | A failed step's JSON line carries the command's own words after Ansible's, a loop's by each failed item's label, cut to their tail within fixed bounds, and a task with `no_log` stays hidden. |
| <a id="ho11"></a>HO11 | The Homeserver's Packages | `up` installs the Matrix transport's and homeserver's packages with the agents and enables nothing; the first task start brings the homeserver up. |
| <a id="ho10"></a>HO10 | A Vault Opened With Nobody There | `up` runs to the end with the vault opened beforehand by the caller over ssh, the passphrase on standard input; the playbook only reads `porelo vault status` and stops, and the Molecule scenario searches its output, the journal and the host's files for the secret. |
| <a id="ho01"></a>HO01 | The Collection's Skeleton | `porelo.host` builds into a tarball checked to hold only what it ships, and its Build runs the documents' checks, yamllint and ansible-lint from one image pinned by digest. |
| <a id="ho03"></a>HO03 | Molecule Tests On A Throwaway VM | `build/molecule.sh` tests the roles on a libvirt VM made and deleted for each run, here and in the Build on GitHub's runner, leaving nothing after a failure or an interrupt. |
| <a id="ho04"></a>HO04 | The Host's Base: Packages, KVM, Podman | The role `porelo.host.base` installs the packages, KVM with libvirt and podman, stopping on a CPU without virtualization first, and puts Porelo's account into KVM's and libvirt's groups. |
| <a id="ho05"></a>HO05 | The VPN | The role `porelo.host.vpn` brings WireGuard up with the host's private key read from the drive by `wg` itself under a local AppArmor rule, loads the `inet porelo_host` nftables table leaving only ssh and the VPN on the public interfaces, and stops the interface and removes its file at `down`. |
| <a id="ho06"></a>HO06 | The Person's Drive Mounted | The role `porelo.host.drive` mounts a `noexec` sparse-file filesystem for the drive, installs the drive's client once its signature under the pinned CA checks and runs its daemon as a user unit, stops `up` for `drive` while logged out, syncs only the person's folder into the folder, and at `down` waits until it is synced before logging out with `--unlink` and unmounting. |
| <a id="ho09"></a>HO09 | Up And Down In Their Order | `playbooks/up.yml` runs in two plays around the waiting stop for what only the person can give and resumes from the top by idempotence, `playbooks/down.yml` takes down what is there in reverse order, and the stdout callback `porelo.host.lines` reports each task and stop as version 1 JSON lines. |
| <a id="ho02"></a>HO02 | Porelo's Own Commands Called, Never Redone | The role `porelo.host.porelo` installs Porelo by its pinned key and runs its setup script for the account, runs `porelo setup`, stops `up` while the vault is missing or shut, deposits the machine file's keys from the drive into `porelo vault put --from-file` and follows the project by its signature, and clears the vault at `down`, every step through Porelo's own commands and answers with no secret passing through Ansible. |
| <a id="ho49"></a>HO49 | The Person's Forge Login Without Repositories | Wherever the host runs its own forge, the person signs in with their lasting password whether or not repositories are migrated into it. |
| <a id="ho84"></a>HO84 | The Person's Daemon By A Fixed Name | A person's tunnel reaches their daemon and binds its return socket by names that follow from the login alone, `porelod.sock` and `return.sock` under `/run/porelo-host/people/<login>/`. |
| <a id="ho93"></a>HO93 | The Host Follows Its Organization By Itself | Every five minutes and at once on `org refresh`, root fetches the signed state of the host's organization through the adapter, checks it against the pin and the last state taken, and applies its persons, projects and members. |
| <a id="ho94"></a>HO94 | A Project Put On The Host And Taken Off | A project is on one host at a time: put on, its members are admitted and its forge organization and repositories stand on the host's forge with the host admin's token once; taken off, it goes once nothing of it is unpushed, and whoever has no other membership is locked. |
| <a id="ho95"></a>HO95 | No Access File, Bring Or Join On The Host | Nothing on the host reads an access file, takes a bundle or runs a bring or a join, and the host's admins name its host admins alone. |
| <a id="ho96"></a>HO96 | The Registry Named The Organization On The Host | The host's verbs, files, answers and documents speak of its organization, never of a registry. |
| <a id="ho42"></a>HO42 | The Host Prepared With No Project Or Person | `up` prepares a host once with no project and no person, and the forge, runner, VM pool and a homeserver in an account of its own are shared by whoever is admitted later. |
| <a id="ho43"></a>HO43 | A Project Admin Admitted By The Host Admin | `porelo-host-person` admits a person from their public keys into an account of their own with a forge login, identities and a Matrix token, and locks and purges them again. |
| <a id="ho44"></a>HO44 | A Project Registered On The Host | `porelo-host-project` registers a project with one forge organization and one publisher and its pinned signing key, and removes it once nothing of it is unpushed. |
| <a id="ho47"></a>HO47 | The Gathered Push Run By Any Developer | Each developer of a project runs the gathered push to the central forge with their own write token and config. |
| <a id="ho53"></a>HO53 | No Forge User Operator | `up` makes no forge user `operator` and removes one a host holds from before, and a person whose name a system group holds is refused. |
| <a id="ho55"></a>HO55 | A Person Leaves When No Membership Is Left | Every person is on the host by a membership and is locked when none is left, and `down` refuses while anyone holds one, naming each. |
| <a id="ho63"></a>HO63 | Podman Reached Through The Account's Session Bus | Every podman call as a service account reaches that account's session bus, and a missing bus refuses loudly, naming the account. |
| <a id="ho74"></a>HO74 | Every Role Tested Without The VPN | Molecule shows every role of the host over SSH alone, through a tunnel with a person's key, with the persons' lock and purge in its act. |
| <a id="ho60"></a>HO60 | Each Repository's Review Set On The Forge | The host takes every repository the signed project file names and protects each one's `main` as its `review` value says, set back at each follow; tested in sprint 6q: passed. |
| <a id="ho100"></a>HO100 | A Project Helper's Own Words In Status | A project helper's refusal reaches `org status`'s `why` and the journal in its own words; tested in sprint 6q: passed. |
| <a id="ho102"></a>HO102 | A Person's Projects Named On The Host | Each follow publishes one file per person naming their projects, readable by that person alone, so `porelo project list` names them before a first start; tested in sprint 6q: passed. |
| <a id="ho103"></a>HO103 | A Project Put On Again After Off | A project taken off and put on again is taken, and `off` says which deploy key stays at the central forge; tested in sprint 6q: passed. |
| <a id="ho104"></a>HO104 | A Refusal's Why Lasts | A refused migration's `why` in `org status` stands until a later try ends otherwise; tested in sprint 6q: passed. |
| <a id="ho108"></a>HO108 | Approved Commits Not Yet At The Central Forge | A person or the interface asks the host, without root or a central token, how each approved `main` stands against the central forge; tested in sprint 6q: passed. |
| <a id="ho110"></a>HO110 | A Server Follows The Organization Admins' Keys | A server's pin holds the Organization Admins' keys beside its Machine Admins, so a Machine Admin they replace takes effect at the next follow; tested in sprint 6q: passed. |
| <a id="ho112"></a>HO112 | The Project File Reaches A Task On The Host | What a Project Admin files at the central forge reaches a task on the host at the next follow; tested in sprint 6q: passed. |

## Rejected

| # | Title | Why |
|---|---|---|
| <a id="ho155"></a>HO155 | A Person Reads How Their Project's Repositories Stand Against Central | `porelo-host-push --central <project>` already gives a project's unlocked members the follow's answer, in their own account and without sudo. |
| <a id="ho22"></a>HO22 | Ssh Reached Only Through The VPN Once It Is Up | replaced by the one-key design; there is no VPN, and SSH on port 22 is the host's only way in. |
| <a id="ho62"></a>HO62 | The Forge's Own Reason For A Refused Password | replaced by the one-key design; the forge has no password. |
| <a id="ho65"></a>HO65 | The Host's Tunnel Addresses Answered | replaced by the one-key design; the host has no tunnel addresses. |
| <a id="ho66"></a>HO66 | The Forge's Links Reach The Laptop | replaced by the one-key design; the forge's links follow `ROOT_URL` at `<host>.localhost`. |
| <a id="ho67"></a>HO67 | The Walk's Host Checks Hold On A Rented Host | replaced by the one-key design; the forge's session bus was built apart, and the rented-host run and the check of the helpers' version went with the work testing every role without the VPN. |
| <a id="ho16"></a>HO16 | The Person's Drive On OneDrive | The host has no drive any more; OneDrive as a user's own drive may come later as a new idea beside the interface's. |
| <a id="ho105"></a>HO105 | A Host's Name On The LAN | The host opens no port for it: a `.local` name does not answer after `up`, and a DNS name or a name entry of one's own takes its place. |
| <a id="ho45"></a>HO45 | The Project Brought And Joined In The Person's Own Account | The host's follow of its organization replaced it: a person connects once per host and finds every project ready, with no bring or join. |
| <a id="ho46"></a>HO46 | The Developers Kept In Step With The Signed Access File | The host's follow of its organization replaced it: members come from the organization's signed state, with no access file or sync. |
| <a id="ho48"></a>HO48 | Every Role Tested On One Host | The follow's Molecule steps and [HO74](#ho74) replaced it; the scenario's bring, people and project steps are gone. |
| <a id="ho56"></a>HO56 | Remove Takes The Person's Push And Sync Away | The follow replaced it: it takes a project's push config out of each person's account itself, and no sync timer exists any more. |
| <a id="ho57"></a>HO57 | The Bring Checks With The Keys In Force After A Handover | The follow replaced it: no second bring exists, and the follow verifies with `--since` from the commit it last took. |
| <a id="ho59"></a>HO59 | An Interrupted Bring Replaced Safely | The follow replaced the bring, so no bring is left to be interrupted. |
| <a id="ho51"></a>HO51 | Down Names What A Transport Left | `down` clears no vault and refuses while a membership stands; what a transport keeps at purge is [HO61](#ho61). |
| <a id="ho64"></a>HO64 | The Collection Tested Where Up Puts It | Only root runs the collection now: `up` and `down` are its playbooks, and no person's account resolves it. |
| <a id="ho82"></a>HO82 | A Bring With No Token File | Bring and join are gone: the person's token comes from `forge-token` on standard output at each run, and a central token on standard input. |
| <a id="ho87"></a>HO87 | The Host's Registry State Answered | `org status` replaced `registry status` and answers the host, the applied commit, the pin and each person's state. |
| <a id="ho88"></a>HO88 | A Project Registered From The Admin's Own Account | The follow makes a project's organization and teams itself, with no person's act and no registry. |

