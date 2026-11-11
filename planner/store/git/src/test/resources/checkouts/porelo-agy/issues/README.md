# Issues

What is open in this repository, grouped by state in the order Review, Rework, Implementing, Backlog,
Analyzed, Accepted, Idea, Estimated, Finished and Rejected; a group with nothing in it is left out.
Within a group, the order is the priority. The number is identity, not sequence.

## Review

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="ag13"></a>[AG13](AG13-Snapshots-Not-Published-On-The-Forge-Come-From-Upstream.md) | S | — | on a forge whose registry holds only its organization's packages, the other Porelo snapshots come from the anonymous repository `MAVEN_UPSTREAM_SNAPSHOTS_URL` names, the forge's token sent to the forge alone |
| <a id="ag08"></a>[AG08](AG08-Subagents-Only-As-Many-As-The-Task-Allows.md) | S | — | no subagent in a task unless the task allows it, switched off by a hook, measured to hold at the pin; a task allowing more is refused by Porelo |
| <a id="ag04"></a>[AG04](AG04-The-Build-Runs-On-The-Local-Forge-As-On-The-Central-One.md) | S | — | the workflows run 1:1 on the local Forgejo and the central forge, legs asked by kind, before the move |

## Backlog

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="ag02"></a>[AG02](AG02-The-Repository-s-Agent-Works-In-A-Porelo-Task.md) | S | [AG04](#ag04) | the repository's agent moves into its own Porelo task, after a dry run on this machine |

## Accepted

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="ag09"></a>[AG09](AG09-Mutation-Testing-With-PIT.md) |  | — | mutation testing by hand: a `pit` profile outside CI with no threshold, and a report sorting survivors into real gaps and harmless ones |

## Idea

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="ag14"></a>[AG14](AG14-The-Subagent-Scenario-Proves-The-Denial-By-What-Ran.md) |  | — | the subagent scenario decides by what the task ran, not by the word the model answers; it failed once on ubuntu and passed on its re-run |
| <a id="ag12"></a>[AG12](AG12-The-Clean-Up-Says-When-Nothing-Was-Rented.md) |  | — | a leg that failed before resolving the machine tooling rented nothing, and its clean-up says so instead of failing on a missing class |
| <a id="ag11"></a>[AG11](AG11-The-Acceptance-Legs-Run-Side-By-Side.md) |  | — | a Build's acceptance legs run at the same time, two machines held at once instead of one, the Build done about one leg sooner |
| <a id="ag06"></a>[AG06](AG06-A-Sign-In-Without-A-Browser-Is-Tested-On-Every-CLI-Update.md) |  | — | the sign-in at a terminal without a browser, checked on every CLI update so a release that drops it fails its pin bump |
| <a id="ag05"></a>[AG05](AG05-The-Acceptance-Legs-Run-On-The-Host-s-Forge.md) |  | [AG04](#ag04) | the acceptance legs on the host's pool again, once it lends a forge job a machine by kind |
| <a id="ag10"></a>[AG10](AG10-Depend-On-The-Agent-Protocol-Porelo-Provides.md) |  | — | the agent contract named at install time |

## Finished

| # | Title | What was built |
|---|---|---|
| <a id="ag07"></a>[AG07](README.md#ag07) | The Sign-In Says How It Is Answered | `agy.yaml` declares `login.flow: paste-code`, so a person signing in to Antigravity CLI from a host is told to open the link and paste the code Google's page shows; tested in sprint 6m: passed. |
| <a id="ag03"></a>[AG03](README.md#ag03) | The Sign-In's Client Is Picked, Not Guessed | The login wrapper collects every OAuth client id (whole, the 13-digit consumer id included) and secret of the CLI, and `AgyCredentialExtractor` hands them to Porelo's login as `clientIdCandidates`/`clientSecretCandidates`, so the login keeps the pair the sign-in was granted to; a live sign-in stored the consumer client and renewed on the host with it. |
| <a id="ag01"></a>[AG01](README.md#ag01) | Agent Antigravity CLI | `porelo-agent-agy` ships Antigravity CLI (`agy`) as a packaged agent with its digest-verified binary download, brokering both the Google sign-in (`cloud-code` via `CLOUD_CODE_URL` with encrypted host-side OAuth renewal) and the Gemini API key (`google-generative-ai` via `GOOGLE_GEMINI_BASE_URL`), with the real credential never in the container and the terms' risk stated in `doc/index.md`. |
