# Issues

The open tasks of `porelo-planner`, grouped by state; the order within a group is the priority. The rules for an
issue, and who sets which state, are in [AGENTS.md](../AGENTS.md). *Open questions*, in *Analyzed* only, counts what
waits on the operator: the items under an issue's own *Open questions*. Its prefix is `PL`. A group with nothing in it is
left out.

## Analyzed

| # | Size | Blocked by | Open questions | What it covers |
|---|---|---|---|---|
| <a id="pl01"></a>[PL01](PL01-The-Planner-Builds-As-One-Native-Executable.md) | M | - | 0 | One GraalVM native executable for any x86-64 CPU from four modules (`core`, `store/api`, `store/git`, `app`), started and answering in a native test, built by the Build workflow |
| <a id="pl02"></a>[PL02](PL02-Only-Its-Owner-Reaches-The-Planner.md) | M | [PL01](README.md#pl01) | 0 | Only the account that started it reaches the planner: loopback and an owner-only token on every call, SSE included |
| <a id="pl03"></a>[PL03](PL03-The-Store-Read-From-Git.md) | L | [PL01](README.md#pl01), every repository's JSON issue head | 0 | The store's interface and its Git implementation: workflow, stories, issue indexes and sub-issues read with Jackson (JSON heads only) and the stories library, with the person's own Git login |
| <a id="pl12"></a>[PL12](PL12-The-Planning-Screen-As-A-Module-Of-Its-Own.md) | M | - | 0 | The window's planning screen as a Flutter package in the module `screen/`, built by Maven only in a full build, tested alone and against the mock mode; embedded by the window through a Git ref |
| <a id="pl04"></a>[PL04](PL04-The-Board-Read-Over-REST.md) | M | [PL03](README.md#pl03) | 0 | The board's reads in the OpenAPI description and served over REST; a story's own state over its issues' derived one |
| <a id="pl10"></a>[PL10](PL10-A-Mock-Mode-For-The-Windows-Tests.md) | M | [PL04](README.md#pl04) | 0 | `--mock` serves the window's mock board through the same API and SSE, moves in memory, a reset between tests |
| <a id="pl05"></a>[PL05](PL05-Moves-Saved-All-Or-None.md) | L | [PL04](README.md#pl04) | 0 | Moves saved all or none against the revision, committed and pushed, the stories index rewritten; working states only by work; a mismatching story state saved on confirmation; a parent Finished only after its sub-issues |
| <a id="pl06"></a>[PL06](PL06-Changes-Told-Over-Server-Sent-Events.md) | M | [PL04](README.md#pl04) | 0 | Changes told over SSE: polling, read now, the planner's own saves, a dropped connection |
| <a id="pl07"></a>[PL07](PL07-The-Work-Rules-Read-From-The-Workflow.md) | S | [PL03](README.md#pl03) | 0 | Which state starts which work read from `stories/work.yml` and checked, its faults answered beside the workflow |
| <a id="pl08"></a>[PL08](PL08-The-Work-Proposal.md) | M | [PL07](README.md#pl07) | 0 | The work proposal: main agent per repository, each sub-issue a task of its own, the operator's choices, comment and reading limit, and the prompts the window starts each task with |
| <a id="pl09"></a>[PL09](PL09-The-Run-From-Building-To-Review.md) | L | [PL08](README.md#pl08) | 0 | The run from building through Reading and Reworking to Review: the reading limit, Needs you and its three choices, findings, a local record; task changes told by the window |
| <a id="pl11"></a>[PL11](PL11-The-Planner-Packaged-For-The-Window.md) | M | [PL01](README.md#pl01) | 0 | A .deb and an .rpm with the native executable, published from the Build like the other Porelo packages |
