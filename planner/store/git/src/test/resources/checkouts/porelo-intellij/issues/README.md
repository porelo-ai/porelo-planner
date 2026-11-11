# Issues

The open tasks of `porelo-intellij`, grouped by state; the rules for an issue are in [AGENTS.md](../AGENTS.md). Its prefix
is `IJ`.

## Review

| # | size | blocked by | what it covers |
|---|---|---|---|
| <a id="ij16"></a>[IJ16](IJ16-An-Open-Review-Shows-That-Its-Push-Moved.md) | S | | A review tab open while its push moves or is decided elsewhere says so within a minute, today's read, and disables *Approve* and *Reject*; within seconds once the daemon's stream names moves |
| <a id="ij17"></a>[IJ17](IJ17-Approve-And-Reject-Named-In-Words.md) | XS | | *Approve* and *Reject* named in words beside their icons at the top of the review tab |
| <a id="ij12"></a>[IJ12](IJ12-The-Code-Review-Adapter-Over-collaborationTools.md) | M | | One narrow, replaceable pure-Java adapter package over `intellij.platform.collaborationTools` for diff threads and multi-line gutter controls on Porelo's diff viewers, verified on 2026.1+ without `qodana-sarif` or internal classes; a spike ran it against stand-in threads |

## Implementing

| # | size | blocked by | what it covers |
|---|---|---|---|
| <a id="ij14"></a>[IJ14](IJ14-Every-Workflow-Runs-On-The-Host-Forge-As-On-GitHub.md) | M | | The workflows run unchanged on the move's host's Forgejo and on GitHub: the IDEs' room made by what the runner has, Publish, the Windows job and run deletion switched by `PORELO_PUBLISH`, Porelo's snapshots from `MAVEN_SNAPSHOTS_URL`; green on a throwaway Forgejo with the forge's image and limits; what only the host can show is named in the issue |

## Backlog

| # | size | blocked by | what it covers |
|---|---|---|---|
| <a id="ij04"></a>[IJ04](IJ04-Code-Comments-In-The-Review.md) | L | | Comments, findings, replies and read-only fix previews in the review tab as SARIF 2.1.0, inline through [IJ12](README.md#ij12)'s adapter and in a discussion panel for file, general and outdated threads, with authenticated source levels and risk classes; the gate's calls and the threads' placing are built behind a stand-in reader, and nothing is shown yet |
| <a id="ij15"></a>[IJ15](IJ15-The-IntelliJ-Agent-Works-In-A-Porelo-Task.md) | S | [IJ14](README.md#ij14) | This repository's agent moves into a Porelo task on the move's host, as the project's procedure for an agent's move lays out; the image on the forge's Ubuntu 24.04, Gradle's user home kept in the task, the move's own change through the gate with the forge's build green |

## Accepted

| # | size | blocked by | what it covers |
|---|---|---|---|
| <a id="ij21"></a>[IJ21](IJ21-Mutation-Testing-With-PIT.md) | | | Mutation testing with PIT over the plugin, run by hand, not in CI and with no threshold, PIT and its JUnit 5 plugin pinned, on a VM where tests call real tools, with a report sorting survivors into real gaps and harmless ones |

## Idea

| # | size | blocked by | what it covers |
|---|---|---|---|
| <a id="ij22"></a>[IJ22](IJ22-A-Disconnected-Machine-Stays-Unconnected.md) | | | A machine the person disconnected in the interface stays listed and unconnected until *Connect*: the `disconnected` mark of `frontend.json` read, and one machine's open stream and tunnel closed when its entry leaves or gains the mark |
| <a id="ij20"></a>[IJ20](IJ20-A-Review-Opens-With-Its-First-File-Shown.md) | | | A review tab always opens with its first file selected and its diff shown; twice in eleven it opened with the file unselected and the diff empty |
| <a id="ij19"></a>[IJ19](IJ19-A-Fixed-Plugin-Loads-Without-A-Restart.md) | | | A fixed plugin unloads and loads into the running IDE without a restart; today the IDE refuses it as not unload-safe and is left without Porelo |
| <a id="ij13"></a>[IJ13](IJ13-Held-And-Outgoing-Forge-Comments-Released-In-IntelliJ.md) | | [IJ04](README.md#ij04) | Held inbound forge comments (`E0`) and outgoing forge comments, replies, suggestions and verdicts shown with their exact destination, operation, commit and content, each released, dismissed, sent or refused singly at its anchor in the review through the daemon; an edit invalidates a prior release |
| <a id="ij11"></a>[IJ11](IJ11-Tried-On-A-Windows-Machine.md) | | | The WSL way and the ssh way tried once on a real Windows machine, with what to install and what to try; what failed goes to the channel, what passed into `doc/` with the plugin's version |
| <a id="ij10"></a>[IJ10](IJ10-Relays-And-Connections-End-Cleanly.md) | | | Relays, WSL bridges and tunnels never outlive their use, leave no socket file or child behind, and a probe never answers with half of what it read; seven older points from a skill reading, each to be reproduced first |

## Finished

| # | Title | What was built |
|---|---|---|
| <a id="ij01"></a>[IJ01](README.md#ij01) | Work is reviewed in IntelliJ | A tool window of every waiting push, its diff read in IntelliJ's own diff windows from a bare cache with nothing checked out, approved with the commit shown or rejected with a reason through the daemon's contract, and the plugin published to a custom plugin repository. |
| <a id="ij02"></a>[IJ02](README.md#ij02) | Live notices and a guide | A waiting push announced within seconds through the daemon's stream of projects, measured at 3 s on a VM, and `doc/` saying how to install and use the plugin. |
| <a id="ij07"></a>[IJ07](README.md#ij07) | The plugin named Porelo | The plugin, its id `org.fuin.porelo`, packages, build and documents named Porelo, speaking to a Porelo daemon and installed afresh. |
| <a id="ij08"></a>[IJ08](README.md#ij08) | Reformatted to the shared style | Google Java Style in its AOSP variant checked by Spotless in `build`, the shared `.editorconfig` checked by `checkShared`, and one commit that only reformats, which `git blame` skips. |
| <a id="ij06"></a>[IJ06](README.md#ij06) | The code read against its skills | All of the plugin's code, tests and build read against the five skills `AGENTS.md` names; 31 defects fixed, 17 of them with a test of their own seen red first. |
| <a id="ij05"></a>[IJ05](README.md#ij05) | A waiting push opened in its project | *Open in* the IntelliJ window whose repository holds the gate's upstream fetches the push there as `porelo/<push>`, leaving the working tree, index and branch as they were; the ref is deleted once decided, and that remote fetched after approving. |
| <a id="ij03"></a>[IJ03](README.md#ij03) | Porelo reached from IntelliJ on Windows | A Porelo in a WSL 2 distribution reached through `wsl.exe` and `porelo daemon connect`, and a machine over ssh through Windows' own `ssh.exe`; the ssh way checked on GitHub's Windows runner on every change, the WSL way against stand-ins on Linux only. |
| <a id="ij18"></a>[IJ18](README.md#ij18) | A host is reached as its card says | Every ssh the plugin starts for a host - tunnel, Windows relay, review fetch - runs with the ssh configuration the interface writes from the host card, so the card's host key is enforced and the person's own ssh configuration cannot redirect it; tested in sprint 6m: passed. |
| <a id="ij09"></a>[IJ09](README.md#ij09) | The build verifies every dependency | Every plugin, library and IDE the Gradle build resolves checked against its SHA-256 before use, a changed digest failing the build by the artifact's name, the file renewed by hand with every version move, and `porelo-buildtools` pinned to a release from Central. |
