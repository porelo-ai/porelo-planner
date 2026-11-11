# Issues

The open tasks of `porelo-planner`, grouped by state; the order within a group is the priority. The rules for an
issue, and who sets which state, are in [AGENTS.md](../AGENTS.md). *Open questions*, in *Analyzed* only, counts what
waits on the operator: the items under an issue's own *Open questions*. Its prefix is `PL`. A group with nothing in it is
left out.

## Idea

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="pl13"></a>[PL13](PL13-One-Planner-Shared-By-A-Hosts-Users.md) | - | - | One planner serving every user of a host as a service, each user signed in, seeing only what their own Git rights let them read, with clones shared where the rights allow |
| <a id="pl14"></a>[PL14](PL14-A-Project-Whose-Repositories-Are-Local-Paths.md) | - | - | Planning a project whose own repository and project.yml repositories are absolute local paths, such as Porelo's Default project or a local checkout |
| <a id="pl15"></a>[PL15](PL15-The-Answer-Types-Generated-From-The-Description.md) | - | - | The planner's answer records, and possibly its controller interfaces, generated from openapi/planner.yaml instead of written by hand |
| <a id="pl25"></a>[PL25](PL25-Findings-In-The-Screens-Older-Code.md) | - | - | Moves staged for one repository of a project are dropped when the project is rebound to another; every agent's or repository's text the screen shows is stripped of control and bidirectional characters |
| <a id="pl26"></a>[PL26](PL26-The-Mock-Board-Saves-Moves.md) | - | - | The mock mode saves moves into its in-memory board with a new revision and a change event, answers PartlySaved for one fixed story, and serves a story with sub-issues so a refused move can be reached |
| <a id="pl27"></a>[PL27](PL27-The-Work-Record-Says-Why-It-Is-Unavailable.md) | - | - | A refusal of the local work record names what was refused, such as a directory another account could replace, so the person can fix it instead of seeing only that the record is unavailable |

## Backlog

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="pl24"></a>[PL24](PL24-Following-And-Stopping-Story-Work-In-The-Screen.md) | M | [PL09](README.md#pl09) | The screen package's calls for a story's run: following and stopping work, the window's task reports, an issue's findings and a reading round's decision, each follow-up task handed to the window's Start once |
| <a id="pl22"></a>[PL22](PL22-The-Planner-Records-The-Stage-Of-An-Issue-It-Moves.md) | - | [PL05](README.md#pl05) | The planner writes the stage line of an issue it moves, in the record's own format, where a repository records issue stats, instead of refusing the move |
| <a id="pl23"></a>[PL23](PL23-A-Board-Says-When-It-Is-Not-Live.md) | - | - | The planning board stands as live only while its changes stream is open, says that it is catching up while it reconnects and that Reload shows the current state once it is not; an issue's screen shows its sub-issues |

## Implementing

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="pl19"></a>[PL19](PL19-A-Storys-Tasks-Name-Their-Machine.md) | M | [PL09](README.md#pl09) | The window's report of a task it started names the machine it runs on, and a story's work answers it per task, so a task opened from the board opens on its machine |
| <a id="pl10"></a>[PL10](PL10-A-Mock-Mode-For-The-Windows-Tests.md) | M | [PL04](README.md#pl04) | `--mock` serves the window's mock board through the same API and SSE, moves in memory, a reset between tests |
| <a id="pl12"></a>[PL12](PL12-The-Planning-Screen-As-A-Module-Of-Its-Own.md) | M | - | The window's planning screen as a Flutter package in the module `screen/`, built by Maven only in a full build, tested alone and against the mock mode; embedded by the window through a Git ref |

## Review

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="pl09"></a>[PL09](PL09-The-Run-From-Building-To-Review.md) | L | [PL08](README.md#pl08) | The run from building through Reading and Reworking to Review: the reading limit, Needs you and its three choices, findings, a local record; task changes told by the window |
| <a id="pl18"></a>[PL18](PL18-Moves-And-Story-Work-In-The-Screen-Package.md) | L | [PL05](README.md#pl05), [PL08](README.md#pl08) | The screen package's HTTP calls for saving moves, proposing story work and ordering it, and the window's Start hook taking each checked task once, so the window can start a story's work |
| <a id="pl08"></a>[PL08](PL08-The-Work-Proposal.md) | L | [PL07](README.md#pl07) | The work proposal: main agent per repository, each sub-issue a task of its own, the operator's choices, comment and reading limit, and the prompts the window starts each task with |
| <a id="pl05"></a>[PL05](PL05-Moves-Saved-All-Or-None.md) | L | [PL04](README.md#pl04) | Moves saved all or none against the revision, committed and pushed, the stories index rewritten; working states only by work; a mismatching story state saved on confirmation; a parent Finished only after its sub-issues |
| <a id="pl21"></a>[PL21](PL21-A-Streamed-Board-Kept-Over-A-Slower-Read.md) | S | [PL17](README.md#pl17) | A board streamed while a reload or a save is reading is never replaced by the older board that read answers |
| <a id="pl17"></a>[PL17](PL17-The-Changes-Stream-In-The-Screen-Package.md) | M | [PL06](README.md#pl06) | The screen package's watchStories over the planner's event stream: the board first, every change after, 45 s of silence taken as a lost connection, reconnect with Last-Event-ID backing off 1 to 30 s |
| <a id="pl20"></a>[PL20](PL20-Three-Findings-In-The-Screen-Package.md) | S | - | The board shows a refusal's own words; a board that failed loads again when its provider changes; the planner's address is trusted only from directories and a file this account owns and others cannot open |
| <a id="pl06"></a>[PL06](PL06-Changes-Told-Over-Server-Sent-Events.md) | M | [PL04](README.md#pl04) | Changes told over SSE: polling, read now, the planner's own saves, a dropped connection |
| <a id="pl01"></a>[PL01](PL01-The-Planner-Builds-As-One-Native-Executable.md) | M | - | One GraalVM native executable for any x86-64 CPU from four modules (`core`, `store/api`, `store/git`, `app`), started and answering in a native test, built by the Build workflow |
| <a id="pl02"></a>[PL02](PL02-Only-Its-Owner-Reaches-The-Planner.md) | M | [PL01](README.md#pl01) | Only the account that started it reaches the planner: loopback and an owner-only token on every call, SSE included |
| <a id="pl11"></a>[PL11](PL11-The-Planner-Packaged-For-The-Window.md) | M | [PL01](README.md#pl01) | A .deb and an .rpm with the native executable, published from the Build like the other Porelo packages |
| <a id="pl03"></a>[PL03](PL03-The-Store-Read-From-Git.md) | L | [PL01](README.md#pl01) | The store's interface and its Git implementation: workflow, stories, issue indexes and sub-issues read with Jackson (JSON heads only) and the stories library, with the person's own Git login |
| <a id="pl07"></a>[PL07](PL07-The-Work-Rules-Read-From-The-Workflow.md) | S | [PL03](README.md#pl03) | Which state starts which work read from `stories/work.yml` and checked, its faults answered beside the workflow |
| <a id="pl04"></a>[PL04](PL04-The-Board-Read-Over-REST.md) | M | [PL03](README.md#pl03) | The board's reads in the OpenAPI description and served over REST; a story's own state over its issues' derived one |
| <a id="pl16"></a>[PL16](PL16-The-Planners-HTTP-Client-In-The-Screen-Package.md) | M | [PL04](README.md#pl04) | A PlanningSource over HTTP in the screen package: planner.json read, the project PUT, the board reads, problem+json as PlanningRefusal; the window wraps it and hands it in |
