# Issues

The open tasks of `porelo-planner`, grouped by state; the order within a group is the priority. The rules for an
issue, and who sets which state, are in [AGENTS.md](../AGENTS.md). *Open questions*, in *Analyzed* only, counts what
waits on the operator: the items under an issue's own *Open questions*. Its prefix is `PL`. A group with nothing in it is
left out.

## Idea

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="pl33"></a>[PL33](PL33-The-Planner-As-A-Service-Of-The-System.md) | - | - | The planner runs as a service the system knows - a systemd user service on Linux, a service on Windows - started, stopped and seen with the system's own commands, instead of a process the window leaves running unseen |
| <a id="pl38"></a>[PL38](PL38-A-Move-Done-At-Once-Warned-Where-It-Can-Do-Harm.md) | - | - | A move on the board is saved at once, without Save and Undo, and each kind of move first warns where it can do harm - running agent tasks to stop or cancel, issues of other repositories moving along, a closed state - and asks the person to confirm or cancel |
| <a id="pl36"></a>[PL36](PL36-A-Description-Shown-In-Its-Own-Format.md) | - | - | Each text a store gives - a story's or an issue's description - comes with the format it is written in, set by the store's adapter (the Git store says Markdown), and the screen renders Markdown and shows any other format as plain text |
| <a id="pl37"></a>[PL37](PL37-A-Storys-Page-With-A-Folding-Description-And-A-Searchable-Issue-List.md) | - | - | A story's description can be folded and unfolded, and its issue list has the window's list design: a search field, filters and sorting |
| <a id="pl43"></a>[PL43](PL43-A-Slow-Save-Told-As-Slow-Not-As-A-Failure.md) | S | - | A save that takes longer than the screen waits is shown as still saving and, once the board shows it done, as saved; two messages never run together |
| <a id="pl44"></a>[PL44](PL44-An-Estimate-Learns-From-The-Tokens-Spent.md) | L | - | When an issue is set Finished, the Git store keeps what its tasks spent in tokens per phase beside its estimated size; a statistic per size and repository is kept from those records, and a task started for an estimate is handed it with the finished issue most like the new one |
| <a id="pl45"></a>[PL45](PL45-A-Story-Moves-With-Its-Issues.md) | M | - | A story's state follows its issues where the hands are the planner's: it stands Implementing once its first issue is, Review once every issue is in Review, Finished or Rejected, and a story set Finished sets its issues still in Review to Finished with it |
| <a id="pl46"></a>[PL46](PL46-An-Issue-Says-Whether-An-Agent-Works-It.md) | M | - | Every issue's head carries who works it, Agent or Manual; the board shows it and lets a person set it, and only an Agent issue gets a task started for it when it reaches a state that starts work |
| <a id="pl47"></a>[PL47](PL47-A-Storys-Size-Beside-Its-Priority.md) | S | - | A story carries its own size, XS to XL, set when it is estimated; the board's answer carries it beside its priority, and an issue's size, which the answer already carries, shows the same way |
| <a id="pl13"></a>[PL13](PL13-One-Planner-Shared-By-A-Hosts-Users.md) | - | - | One planner serving every user of a host as a service, each user signed in, seeing only what their own Git rights let them read, with clones shared where the rights allow |
| <a id="pl14"></a>[PL14](PL14-A-Project-Whose-Repositories-Are-Local-Paths.md) | - | - | Planning a project whose own repository and project.yml repositories are absolute local paths, such as Porelo's Default project or a local checkout |
| <a id="pl15"></a>[PL15](PL15-The-Answer-Types-Generated-From-The-Description.md) | - | - | The planner's answer records, and possibly its controller interfaces, generated from openapi/planner.yaml instead of written by hand |

## Accepted

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="pl35"></a>[PL35](PL35-A-Test-App-For-The-Planning-Screen.md) | - | - | A small Flutter app that embeds only the planning screen against a mock: what the window hands the screen is entered by hand, every call the screen makes toward the window is shown, and the window's events back - a task started, ended or stopped, its answer - are sent by hand to see the board react |
| <a id="pl41"></a>[PL41](PL41-The-Git-Store-Alone-Handles-Stories-And-Issues.md) | XL | - | Stories and issues kept as files in Git are read, checked, indexed and written by the planner's Git store alone - the shared stories library's code taken into store/git, a broken head and a broken issue link shown as faults on the board, a new idea written through the planner, every repository's issues checked against the project's one workflow, and each issue's stages recorded so the project's velocity goes to an estimate - so the build tools can drop the library and their story, issue, stage and link commands |

## Backlog

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="pl25"></a>[PL25](PL25-Findings-In-The-Screens-Older-Code.md) | - | - | Moves staged for one repository of a project are dropped when the project is rebound to another; every agent's or repository's text the screen shows is stripped of control and bidirectional characters; a held-back board is not live, each watch tells its own reconnect, and a disposed board opens no watch |
| <a id="pl26"></a>[PL26](PL26-The-Mock-Board-Saves-Moves.md) | - | - | The mock mode saves moves into its in-memory board with a new revision and a change event, answers PartlySaved for one fixed story, and serves a story with sub-issues so a refused move can be reached |
| <a id="pl27"></a>[PL27](PL27-The-Work-Record-Says-Why-It-Is-Unavailable.md) | - | - | A refusal of the local work record names what was refused, such as a directory another account could replace, so the person can fix it instead of seeing only that the record is unavailable |
| <a id="pl29"></a>[PL29](PL29-The-Windows-Executable-Built-Tested-And-Shipped.md) | L | [PL28](README.md#pl28) | A GraalVM native porelo-planner.exe for Windows x86-64, built and tested on GitHub's Windows runner in the Build, packed as a ZIP and a signed MSIX that puts it on the PATH, with SHA256SUMS and a build provenance attestation, and published beside the Linux packages |
| <a id="pl30"></a>[PL30](PL30-The-Screen-Finds-The-Planner-On-Windows.md) | M | [PL29](README.md#pl29) | The screen package's HTTP source reads planner.json from %LOCALAPPDATA%\porelo-planner on Windows and refuses one another account could read, replace or redirect, checked through the file's owner and access control list |
| <a id="pl42"></a>[PL42](PL42-A-Project-On-Any-Host-And-Owner.md) | S | [PL41](README.md#pl41) | The Git store reads a project whose repositories live on any host and under any owner: each central from any clone URL - https, ssh or scp-like - and the link pattern and host of story texts and indexes from project.yml instead of one fixed owner |

## Implementing

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="pl12"></a>[PL12](PL12-The-Planning-Screen-As-A-Module-Of-Its-Own.md) | M | - | The window's planning screen as a Flutter package in the module `screen/`, built by Maven only in a full build, tested alone and against the mock mode; embedded by the window through a Git ref |
| <a id="pl10"></a>[PL10](PL10-A-Mock-Mode-For-The-Windows-Tests.md) | M | [PL04](README.md#pl04) | `--mock` serves the window's mock board through the same API and SSE, moves in memory, a reset between tests |
| <a id="pl39"></a>[PL39](PL39-A-Board-That-Answers-At-Once-With-Many-Stories.md) | M | - | Folding a column, searching and every other change answers at once on a board of a few hundred stories, measured in profile mode before anything is optimized |

## Review

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="pl16"></a>[PL16](PL16-The-Planners-HTTP-Client-In-The-Screen-Package.md) | M | [PL04](README.md#pl04) | A PlanningSource over HTTP in the screen package: planner.json read, the project PUT, the board reads, problem+json as PlanningRefusal; the window wraps it and hands it in |
| <a id="pl20"></a>[PL20](PL20-Three-Findings-In-The-Screen-Package.md) | S | - | The board shows a refusal's own words; a board that failed loads again when its provider changes; the planner's address is trusted only from directories and a file this account owns and others cannot open |
| <a id="pl31"></a>[PL31](PL31-A-Short-Event-Id-For-Any-Number-Of-Repositories.md) | S | - | The Git store's revision is a fixed-length digest of its repositories' commits, at most 200 characters as the screen accepts, so a project with many repositories keeps its live board |
| <a id="pl32"></a>[PL32](PL32-The-Planner-Run-From-The-Source-With-Restarts.md) | XS | - | A Maven profile dev runs the planner from the source on the JVM with Spring Boot DevTools, restarted within a second whenever any module compiles anew, for testing against real projects with the window |
| <a id="pl34"></a>[PL34](PL34-The-Board-Laid-Out-As-It-Is-Used.md) | XS | - | A folded column shows each story in one even row of mark, number and menu; the board's sideways scrollbar stays at the foot of the view, so a wide column's controls can be reached; the list of unsaved moves takes only the height it needs |
| <a id="pl01"></a>[PL01](PL01-The-Planner-Builds-As-One-Native-Executable.md) | M | - | One GraalVM native executable for any x86-64 CPU from four modules (`core`, `store/api`, `store/git`, `app`), started and answering in a native test, built by the Build workflow |
| <a id="pl03"></a>[PL03](PL03-The-Store-Read-From-Git.md) | L | [PL01](README.md#pl01) | The store's interface and its Git implementation: workflow, stories, issue indexes and sub-issues read with Jackson (JSON heads only) and the stories library, with the person's own Git login |
| <a id="pl17"></a>[PL17](PL17-The-Changes-Stream-In-The-Screen-Package.md) | M | [PL06](README.md#pl06) | The screen package's watchStories over the planner's event stream: the board first, every change after, 45 s of silence taken as a lost connection, reconnect with Last-Event-ID backing off 1 to 30 s |
| <a id="pl02"></a>[PL02](PL02-Only-Its-Owner-Reaches-The-Planner.md) | M | [PL01](README.md#pl01) | Only the account that started it reaches the planner: loopback and an owner-only token on every call, SSE included |
| <a id="pl04"></a>[PL04](PL04-The-Board-Read-Over-REST.md) | M | [PL03](README.md#pl03) | The board's reads in the OpenAPI description and served over REST; a story's own state over its issues' derived one |
| <a id="pl11"></a>[PL11](PL11-The-Planner-Packaged-For-The-Window.md) | M | [PL01](README.md#pl01) | A .deb and an .rpm with the native executable, published from the Build like the other Porelo packages |
| <a id="pl05"></a>[PL05](PL05-Moves-Saved-All-Or-None.md) | L | [PL04](README.md#pl04) | Moves saved all or none against the revision, committed and pushed, the stories index rewritten; working states only by work; a mismatching story state saved on confirmation; a parent Finished only after its sub-issues |
| <a id="pl06"></a>[PL06](PL06-Changes-Told-Over-Server-Sent-Events.md) | M | [PL04](README.md#pl04) | Changes told over SSE: polling, read now, the planner's own saves, a dropped connection |
| <a id="pl07"></a>[PL07](PL07-The-Work-Rules-Read-From-The-Workflow.md) | S | [PL03](README.md#pl03) | Which state starts which work read from `stories/work.yml` and checked, its faults answered beside the workflow |
| <a id="pl08"></a>[PL08](PL08-The-Work-Proposal.md) | L | [PL07](README.md#pl07) | The work proposal: main agent per repository, each sub-issue a task of its own, the operator's choices, comment and reading limit, and the prompts the window starts each task with |
| <a id="pl09"></a>[PL09](PL09-The-Run-From-Building-To-Review.md) | L | [PL08](README.md#pl08) | The run from building through Reading and Reworking to Review: the reading limit, Needs you and its three choices, findings, a local record; task changes told by the window |
| <a id="pl18"></a>[PL18](PL18-Moves-And-Story-Work-In-The-Screen-Package.md) | L | [PL05](README.md#pl05), [PL08](README.md#pl08) | The screen package's HTTP calls for saving moves, proposing story work and ordering it, and the window's Start hook taking each checked task once, so the window can start a story's work |
| <a id="pl19"></a>[PL19](PL19-A-Storys-Tasks-Name-Their-Machine.md) | M | [PL09](README.md#pl09) | The window's report of a task it started names the machine it runs on, and a story's work answers it per task, so a task opened from the board opens on its machine |
| <a id="pl24"></a>[PL24](PL24-Following-And-Stopping-Story-Work-In-The-Screen.md) | M | [PL09](README.md#pl09) | The screen package's calls for a story's run: following and stopping work, the window's task reports, an issue's findings and a reading round's decision, each follow-up task handed to the window's Start once |
| <a id="pl21"></a>[PL21](PL21-A-Streamed-Board-Kept-Over-A-Slower-Read.md) | S | [PL17](README.md#pl17) | A board streamed while a reload or a save is reading is never replaced by the older board that read answers |
| <a id="pl23"></a>[PL23](PL23-A-Board-Says-When-It-Is-Not-Live.md) | S | - | The planning board stands as live only while its changes stream is open, says that it is catching up while it reconnects and that Reload shows the current state once it is not; an issue's screen shows its sub-issues |
| <a id="pl28"></a>[PL28](PL28-The-Planners-Own-Files-On-Windows.md) | M | - | On Windows the planner keeps its address file, work record and clones under %LOCALAPPDATA%\porelo-planner, each closed to other accounts through its access control list, with the same refusals as on Linux |

## Rejected

| # | Title | Why |
|---|---|---|
| <a id="pl22"></a>PL22 | The Planner Records The Stage Of An Issue It Moves | The Git store records every moved issue's stage as part of handling stories and issues alone ([PL41](README.md#pl41)). |
| <a id="pl40"></a>PL40 | A Story's Work History Kept In The Store | A story's run is worth keeping only while it works, and Git's commits are its history; what must be kept is a task's results, a story of its own across the repositories |
