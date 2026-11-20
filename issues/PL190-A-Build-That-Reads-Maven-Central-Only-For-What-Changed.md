# PL190 — A Build That Reads Maven Central Only For What Changed

```json
{
  "$schema": "issue-1.json",
  "id": "PL190",
  "title": "A Build That Reads Maven Central Only For What Changed",
  "state": "Implementing",
  "size": "S",
  "priority": "High",
  "rank": 3,
  "blockedBy": [],
  "covers": "The Build's Maven cache takes in what a build fetched even when its key did not change, and no dependency is resolved through a version range, so a Build after an exact cache hit reads nothing from Maven Central that it read before, and a rate limit there does not fail it"
}
```

**Why this size.** S: the cache steps of two workflow files and the one place a version range enters the build, with
the Build's own log as the measure; no code, no contract.

**What must be true.** A Build fails only for a fault in what it builds, not because Maven Central refuses a runner
that asks it again for what an earlier Build already fetched.

## Plan

- Seen on main's Build of 68b226a, job *Build, packages and publish*: the Maven cache was restored by its exact key
  (`maven-Linux-<hashFiles('**/pom.xml')>`), yet the Git store's dependencies were resolved from Maven Central, which
  answered 429 for six poms (Jackson 2.21.7, ArchUnit 1.4.2, commons-codec 1.21.0, slf4j-nop 2.0.20) and for the
  `maven-metadata.xml` of two Bouncy Castle artifacts; the module failed after four 30 s timeouts.
- Read in the log and the workflows, the causes:
  - **The key misses what changes the dependencies.** The versions of those six are named in no pom of this
    repository; they come from `porelo-parent` 0.2.2-SNAPSHOT and the BOMs it and this repository import, which a
    Build downloads fresh. `hashFiles('**/pom.xml')` stays the same when they move, and `actions/cache` never saves
    over an exact hit, so every Build after such a move downloads them again until a pom here changes.
  - **A version range resolves online every Build.** The `maven-metadata.xml` of `bcutil-jdk18on` and
    `bcprov-jdk18on` was asked of Central although the cache was there: a dependency names them by a range, which the
    resolver checks remotely each time. Where the range enters, through a Porelo snapshot (inferred), is to be read
    with `dependency:tree -Dverbose` before the fix.
- The fix: the cache's key gains the run's identity with the current prefix as its restore key
  (`maven-<os>-<hashFiles>-<run id>`, restoring `maven-<os>-<hashFiles>-` then `maven-<os>-`), so each Build saves
  what it fetched and the next one finds it; old entries are evicted by GitHub's own limit. In `build.yml` and
  `windows.yml` alike. The range is replaced by a fixed version in the repository that declares it, asked of its
  owner when that is not this one, and pinned here in `dependencyManagement` until then.
- No harm test: a workflow's cache and a dependency's version; the measure is the Build's log.

## Acceptance

- Two Builds of main in a row with no pom changed: the second one's log has no `Downloading from central` line, and
  no `maven-metadata.xml` of a release is asked of Central.
