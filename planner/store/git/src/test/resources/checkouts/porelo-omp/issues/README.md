# Issues

What is open in this repository, grouped by state in the order Review, Rework, Implementing, Backlog,
Analyzed, Accepted, Idea, Estimated, Finished and Rejected; a group with nothing in it is left out.
Within a group, the order is the priority. The number is identity, not sequence.

## Review

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="om36"></a>[OM36](OM36-The-Repository-s-Agent-Works-In-A-Porelo-Task.md) | S | [OM35](#om35) | the repository's agent moves into its own Porelo task, after a dry run on this machine |
| <a id="om45"></a>[OM45](OM45-Snapshots-Not-Published-On-The-Forge-Come-From-Upstream.md) | S | — | on a forge whose registry holds only its organization's packages, the other Porelo snapshots come from the anonymous repository `MAVEN_UPSTREAM_SNAPSHOTS_URL` names, the forge's token sent to the forge alone, a tag still built from releases only |
| <a id="om41"></a>[OM41](OM41-Subagents-Only-As-Many-As-The-Task-Allows.md) | S | — | no subagent in a task unless the task allows it, then at most as many and as deep as it says; off (depth 0), count and depth measured to hold at the pin |
| <a id="om35"></a>[OM35](OM35-The-Build-Runs-On-The-Local-Forge-As-On-The-Central-One.md) | S | — | the workflows run 1:1 on the local Forgejo and the central forge: snapshots from `MAVEN_SNAPSHOTS_URL`, publishing switched by `PORELO_PUBLISH`, legs asked by kind, green on a local Forgejo |
| <a id="om38"></a>[OM38](OM38-Snapshots-Resolved-With-A-Read-Only-Token.md) | S | — | on a forge other than the central one, snapshots resolved with the runner's read-only token, the publisher's token in no step, since nothing here deploys to the registry, and a Build without `MAVEN_SNAPSHOTS_URL` refused at its start |
| <a id="om39"></a>[OM39](OM39-The-Busy-Check-Waits-On-A-Tool-The-Scenario-Ends.md) | S | — | the busy check of `wake.feature`'s message-while-it-works scenario waits on a loop the scenario ends, not on how the model schedules a `sleep 10` |

## Backlog

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="om18"></a>[OM18](OM18-Prove-GitHub-Copilot-By-Hand-Before-A-Release.md) | S | — | the Copilot path proven by hand before a release |
| <a id="om44"></a>[OM44](OM44-The-Update-Request-Test-Stands-Alone-In-A-Task.md) |  | — | `UpdateRequestTest` runs its scripts in an environment of its own, so a task's `GIT_CONFIG_*` variables are not taken for a leaked header |

## Analyzed

| # | Size | Blocked by | What it covers | Open questions |
|---|---|---|---|---|
| <a id="om19"></a>[OM19](OM19-Declare-A-Thinking-Level-And-A-Read-Only-Mode.md) | S | — | `--thinking` on the five effort levels and a `read-only` mode without edit tools, for a reviewer | 0 |

## Accepted

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="om42"></a>[OM42](OM42-Mutation-Testing-With-PIT.md) |  | — | mutation testing by hand: a `pit` profile outside CI with no threshold, and a report sorting survivors into real gaps and harmless ones |

## Idea

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="om47"></a>[OM47](OM47-The-Clean-Up-Says-When-Nothing-Was-Rented.md) |  | — | a leg that failed before resolving the machine tooling rented nothing, and its clean-up says so instead of failing on a missing class |
| <a id="om46"></a>[OM46](OM46-The-Acceptance-Legs-Run-Side-By-Side.md) |  | — | a Build's acceptance legs run at the same time, two machines held at once instead of one, the Build done about one leg sooner |
| <a id="om37"></a>[OM37](OM37-The-Acceptance-Legs-Run-On-The-Host-s-Forge.md) |  | [OM35](#om35) | the acceptance legs on the host's pool again, once it lends a forge job a machine by kind |
| <a id="om34"></a>[OM34](OM34-The-Shared-Workflow-Tests-See-Every-Refusal-Red.md) |  | — | the shared workflow tests see every publishing and channel refusal red, and the refused-key scenario runs in CI |
| <a id="om33"></a>[OM33](OM33-Read-Oh-My-Pi-s-Sign-Ins-From-Its-Database.md) |  | — | a nice to have: `vault import` and the findings read Oh My Pi's sign-ins from its SQLite database |
| <a id="om43"></a>[OM43](OM43-Depend-On-The-Agent-Protocol-Porelo-Provides.md) |  | — | the agent contract named at install time |

## Finished

| # | Title | What was built |
|---|---|---|
| <a id="om32"></a>[OM32](README.md#om32) | Report What Oh My Pi Finds Of Itself On The Host | The adapter reports Oh My Pi set up on the host by its `agent.db`, which it never opens, with its sign-in "cannot say"; `omp.yaml` names `config_dir`. |
| <a id="om40"></a>[OM40](README.md#om40) | The Sign-In Says How It Is Answered | `omp.yaml` declares `login.flow: device-code`, so a person granting Oh My Pi's GitHub Copilot sign-in from a host is told to confirm the code on GitHub's device page; tested in sprint 6m: passed. |
| <a id="om30"></a>[OM30](README.md#om30) | Read Oh My Pi 18.11's Start Screen As Ready | `omp.yaml` declares `📁 /workspace`, the start of the status line under omp's prompt, as the ready marker in place of the welcome box 18.11 dropped, and the check still fails while the setup wizard is up. |
| <a id="om29"></a>[OM29](README.md#om29) | Take The Legs From A Run That Proved The Same Tree | A Build on `main` asks `porelo-release proven-legs` whether a Build or an update run already proved the pushed tree on both legs within a day and takes those legs instead of renting again, while a tag always runs its own. |
| <a id="om25"></a>[OM25](README.md#om25) | Declare The App Copilot Is Granted Through | `omp.yaml` declares the OpenCode OAuth app (`Ov23li8tweQw6odWQebz`) Oh My Pi signs in with on `github.com` under `login.grants.github-copilot`, the weekly update stops when a new pin signs in with another, and `doc/index.md` names the app and that Enterprise hosts are not offered. |
| <a id="om31"></a>[OM31](README.md#om31) | Publish Without A Downloaded Client | Both Publish steps in `build.yml` upload the packages and their bill of materials through `.github/scripts/artifactory.sh` with `curl`, with no JFrog CLI action or `jf` command left under `.github/`. |
| <a id="om28"></a>[OM28](README.md#om28) | Reformat To The Shared Style | The code is in the shared style, google-java-format in its AOSP variant with YAML and Gherkin at four spaces; `validate` refuses code that is not, and `git blame` with `.git-blame-ignore-revs` skips the reformatting. |
| <a id="om26"></a>[OM26](README.md#om26) | Read The Code Changed Since The Last Skills Review | The code changed since 2026-09-27 read against seven skills, each finding fixed, with a test where it could do harm: no terminal control character reaches a log, and no checkout keeps the job's token. |
| <a id="om27"></a>[OM27](README.md#om27) | Rename The Adapter Porelo | The repository, its artifact, its packages, its Java packages and its documents say Porelo; a documents test keeps the old name to the released part of `CHANGELOG.md`. |
| <a id="om24"></a>[OM24](README.md#om24) | A JSON argument from GLM-4.6 arrives cut off | `doc/index.md` names `z-ai/glm-4.6` as unable to send a message with Oh My Pi, and `z-ai/glm-5.3-flash` as one that can. |

## What lives here and what in Porelo

An agent's work lives in its own repository, so the requirements for this agent are issues here, and
what outlived the met ones is in [`doc/decisions.md`](../doc/decisions.md).
The candidate agents without a repository wait in `porelo-project` until a repository builds them.

## Where the rest of the open work lives

Not everything open is an issue. Accepted risks - exposures that are known, deliberate and not
being removed - are in [`doc/decisions.md`](../doc/decisions.md) with what would change the
answer. A finding that has been answered is neither: it is in the code, with its reasoning.
