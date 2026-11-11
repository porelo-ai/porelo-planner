# Issues

The open tasks of `porelo-ai.github.io`, grouped by state; the order within a group is the priority. The rules for an
issue, and who sets which state, are in [AGENTS.md](../AGENTS.md). *Open questions*, in *Analyzed* only, counts what
waits on the operator: the items under an issue's own *Open questions*. Its prefix is `SI`. A group with nothing in it is
left out.


## Implementing

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="si05"></a>[SI05](SI05-The-Site-Builds-In-A-Porelo-Task.md) | - | - | The site's check green in a Porelo task: `porelo.yml` without `COPY`, its Debian base built once porelo can. |

## Idea

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="si06"></a>[SI06](SI06-The-Sites-Agent-Works-In-A-Porelo-Task.md) | - | [SI05](README.md#si05) | The site's agent works in a Porelo task on the move's host as `Agent Docsite` and talks in the room; its build's Pages upload and its reading of GitHub are first switched by variables on both forges |

## Estimated

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="si01"></a>[SI01](SI01-The-Site-Moves-To-Zensical.md) | M | - | The site moves to Zensical after the operator's test now under way, whatever its version, pinned by hash and compared page by page. |
| <a id="si04"></a>[SI04](SI04-The-Site-Looks-Like-Porelos-Website.md) | S | - | The documentation site in the website's look: dark only, its colours, fonts, glass surfaces and logo, the theme's navigation kept, nothing loaded from another origin; built after the rename window. |

## Finished

| # | Title | What was built |
|---|---|---|
| <a id="si02"></a>SI02 | The Menu Grouped By What A Part Is | Six tabs - porelo, agents, build readers, messaging, ui, internal - each repository a section beneath them, a repository not mapped a tab of its own, and the landing page out of the menu. |
| <a id="si03"></a>SI03 | Renamed Porelo Here | The site is Porelo's, published from `porelo-ai.github.io` with the secret `PORELO_DOCS_READ` and assembled from the renamed repositories; a documents test refuses the old word. |
