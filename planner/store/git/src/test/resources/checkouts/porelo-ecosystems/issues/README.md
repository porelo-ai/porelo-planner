# Issues

The open tasks of `porelo-ecosystems`, grouped by state; the order within a group is the priority. The rules for an
issue, and who sets which state, are in [AGENTS.md](../AGENTS.md). Its prefix is `EC`. A group with nothing in it is left
out.

## Review

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="ec01"></a>[EC01](EC01-A-Spike-Of-The-Model-Builder-In-A-Native-Image.md) | S | - | Whether MIMA serves the Maven plugin as a native image, by measurement: it does, and the bare model builder is proposed instead. |
| <a id="ec08"></a>[EC08](EC08-The-Maven-Plugin-Published-As-A-Recommended-Package.md) | M | - | The Maven plugin published as a package of its own that Porelo's package recommends: installed by default, and it can be left out or removed. |
| <a id="ec03"></a>[EC03](EC03-The-Maven-Plugin-Behind-The-Contract.md) | M | - | The Maven plugin as a native executable in its own package, answering Porelo over its contract with the tools and rules a checkout needs. |
| <a id="ec10"></a>[EC10](EC10-The-Reason-A-Plugin-Has-Nothing-To-Secure.md) | XS | - | The rule that a plugin has nothing to secure says its real reason: the plugin runs no code and stays bounded, its answer is untrusted input, and the person's confirmation is the control. |
| <a id="ec16"></a>[EC16](EC16-A-Project-Behind-An-In-House-Repository-Manager-Builds-In-A-Task.md) | M | - | A multi-module Maven project behind an in-house repository manager builds in a task: a JDK stands in when its parent went unread, a mirror that takes every repository reaches the task's settings, a credential is named in its form without its value, and settings that do not parse are warned of. |
| <a id="ec17"></a>[EC17](EC17-The-Maven-Plugin-Says-Its-Version.md) | S | - | The Maven plugin answers with the version it was built as when Porelo asks it to describe itself, taken from its build, so a person can tell which build answered. |

## Implementing

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="ec13"></a>[EC13](EC13-The-Maven-Adapter-Judged-By-Real-Builds.md) | L | - | The Maven adapter judged by whether a real build runs in a real task, on a corpus of open source projects, with each fault fixed in the Java code alone. |

## Backlog

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="ec11"></a>[EC11](EC11-The-Word-Ecosystem-Adapter.md) | M | - | "Ecosystem adapter" replaces "plugin" for what Porelo finds at run time, before a first release fixes the word: `plugins/` becomes `adapters/`, with every text a person reads; the package and installed paths stay. |
| <a id="ec12"></a>[EC12](EC12-A-Default-Host-Says-It-Is-Maven-s-Own.md) | XS | - | A host that comes from no file, such as Maven's built-in Central, says in its note that it is Maven's built-in default. |

## Analyzed

| # | Size | Blocked by | Open questions | What it covers |
|---|---|---|---|---|
| <a id="ec14"></a>[EC14](EC14-A-Toolchain-Plugin-Is-Warned-Of.md) | S | - | 0 | A plugin that picks or fetches its own JDK, `toolchains-maven-plugin` or Maven's own, gets a warning at its line naming the JDK it asks, instead of a build stopping on a refused host or a missing toolchain. |

## Accepted

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="ec04"></a>[EC04](EC04-The-Gradle-Plugin.md) | - | - | A checkout built with Gradle gets its tools and rules recognized by a plugin of its own, behind the `Ecosystem1` contract, under this repository's rules: never running the project's code, no network while it reads a checkout, every value with its file and line. |
| <a id="ec05"></a>[EC05](EC05-The-npm-Plugin.md) | - | - | A checkout built with npm gets its tools and rules recognized by a plugin of its own, behind the `Ecosystem1` contract, under this repository's rules: never running the project's code, no network while it reads a checkout, every value with its file and line. |
| <a id="ec06"></a>[EC06](EC06-The-pip-Plugin.md) | - | - | A checkout built with pip gets its tools and rules recognized by a plugin of its own, behind the `Ecosystem1` contract, under this repository's rules: never running the project's code, no network while it reads a checkout, every value with its file and line. |
| <a id="ec07"></a>[EC07](EC07-The-Flutter-Plugin.md) | - | - | A checkout built with Flutter gets its tools and rules recognized by a plugin of its own, behind the `Ecosystem1` contract, under this repository's rules: never running the project's code, no network while it reads a checkout, every value with its file and line. |

## Idea

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="ec15"></a>[EC15](EC15-What-The-Exact-Version-Step-Left-Unread.md) | - | - | What installing a project's tools in their exact version left unmeasured: a replaced `image.run`, the HTTP client's size, GraalVM's next asset names, Python's digests, and the online step's time. |

## Finished

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="ec09"></a>EC09 | L | - | A Project's Tools Installed In The Exact Version: what a project asks is installed in the exact version its vendor names, found once when its file is made, as ordinary image lines fetching by URL and digest, with its paths set and GraalVM brought for a native build. |
| <a id="ec02"></a>EC02 | M | - | Maven Repository Hosts Recognized From A Checkout And The Settings: the Maven module answers the repository hosts a real build would reach, compared with real Maven, each with its file, line and origin. |
