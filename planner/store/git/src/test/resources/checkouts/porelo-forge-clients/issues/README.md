# Issues

The open tasks of `porelo-forge-clients`, grouped by state; the order within a group is the priority. The rules for an
issue, and who sets which state, are in [AGENTS.md](../AGENTS.md). *Open questions*, in *Analyzed* only, counts what
waits on the operator: the items under an issue's own *Open questions*. Its prefix is `FC`. A group with nothing in it
is left out.

## Analyzed

| # | Size | Blocked by | Open questions | What it covers |
|---|---|---|---|---|
| <a id="fc03"></a>[FC03](FC03-The-Forge-Calls-Of-The-Adapters-Move-Here.md) | M | - | 0 | The clients make every call Porelo's build adapters make to GitHub and Forgejo today, with the same requests and answers, published as snapshots |

## Accepted

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="fc17"></a>[FC17](FC17-Mutation-Testing-With-PIT.md) | - | - | A `pit` profile run by hand, with PIT and its JUnit 5 plugin pinned, and a report sorting the survivors into real gaps and harmless ones |

## Idea

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="fc15"></a>[FC15](FC15-Sources-And-Javadoc-Jars-In-A-Release.md) | - | - | A release carries each client's sources and javadoc jars, which Maven Central requires and the release build does not make |
| <a id="fc16"></a>[FC16](FC16-GitHubs-Live-Tests-Through-The-App-Token.md) | - | - | The GitHub recorder and live check run with the App's installation token and the reviewer test account's tokens, with no person's own token |

## Finished

| # | What was built |
|---|---|
| <a id="fc10"></a>FC10 — A Pull Request's Commits On GitHub | A GitHub pull request's commits read page by page, each with its revision, the author's and committer's linked accounts and GitHub's signature verdict, recorded and checked live. Not checked: a `Bot` author's `databaseId` in GraphQL, and whether a page past the 250 commits is empty. |
| <a id="fc11"></a>FC11 — A Pull Request's Commits On Forgejo | A Forgejo pull request's commits read page by page, each with its revision, the author's and committer's linked accounts and Forgejo's signature verdict, and a cap on the list said through `X-HasMore`. |
| <a id="fc13"></a>FC13 — GitHub's Nulls For A Deleted Fork And An Empty Review | A GitHub pull request from a deleted fork (`head.repo`, and with the account gone `head.user` and `head.label`, null) and a review with a null `body` read instead of failing the read. |
| <a id="fc09"></a>FC09 — Rate Limits And Bounds Of The Clients' Answers | A rate limit told from a refusal with the delay to wait, the end of a paged list known, whether a failed request was sent, and every answer read up to a bound, on both forges; described in their READMEs |
| <a id="fc14"></a>FC14 — The GitHub Recorder's Ownership Of What It Made | The GitHub recorder keeps owning what an earlier request made when a later one of its kind is refused, and the key page refusal test fails when the page does not stop. |
| <a id="fc12"></a>FC12 — GitHub's Recorded Answers For Other Repositories | GitHub's recorded answers and the replaying `HttpClient` in a `tests` jar of the github module, published beside the main jar for the adapters' tests; described in [clients/github/README.md](../clients/github/README.md). |
| <a id="fc06"></a>FC06 — Keys, Repositories And Commit Statuses On GitHub | A GitHub user's public SSH authentication and signing keys and key page read without a token, the signed-in user's own keys of both kinds listed, added and deleted, whether a user owns an organization, a repository read and created in an organization and a commit's combined status, recorded live; described in [clients/github/README.md](../clients/github/README.md). |
| <a id="fc05"></a>FC05 — Keys And Repositories On Forgejo | A Forgejo user's public SSH keys read, the signed-in user's own keys listed, added and deleted, whether a user owns an organization, a repository read and created in an organization; described in [clients/forgejo/README.md](../clients/forgejo/README.md). |
| <a id="fc02"></a>FC02 — The Forgejo Client | The `forgejo` module: REST operations from Forgejo 17's MIT Swagger 2.0 description and signed webhook deliveries, its answers recorded from a real Forgejo and every call checked live against one in the Build; described in [clients/forgejo/README.md](../clients/forgejo/README.md). |
| <a id="fc08"></a>FC08 — Comments, Threads And Versions Through The Clients | GitHub's general comments, review comment edits and deletes, one review's comments, the merge base, pull requests by head branch and one review thread by its id with its resolver; Forgejo's general comments, review comments, the pull request of two branches and the server version, with what Forgejo cannot do measured; recorded live and from the container; described in both clients' READMEs. |
| <a id="fc01"></a>FC01 — The GitHub Client | The `github` module: 18 REST operations from GitHub's OpenAPI description and review threads over GraphQL, tested strictly against answers recorded live and scrubbed, with the hand-run recorder and the live check; described in [clients/github/README.md](../clients/github/README.md). |
| <a id="fc07"></a>FC07 — Plain Key Lists On Forgejo | `ForgejoClient.plainKeys` reads an account's public SSH keys from its `<login>.keys` page without a token, and a missing login is told apart from any other failure. |
| <a id="fc04"></a>FC04 — Records Generated From A Pinned OpenAPI Description | Records generated at build time by openapi-generator from a pinned, filtered OpenAPI 3.1 or Swagger 2.0 extract with overridden templates, the `openapi-extract` command and the `openapi-records` check; described in [doc/records.md](../doc/records.md). |
