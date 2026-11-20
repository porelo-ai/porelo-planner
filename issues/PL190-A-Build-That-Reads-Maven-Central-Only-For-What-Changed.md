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

- Seen on main's Builds: with the Maven cache restored by its exact key (`maven-Linux-<hashFiles('**/pom.xml')>`),
  the Git store's dependencies were still asked of Maven Central, which answered 429 for six poms and for the
  `maven-metadata.xml` of `bcutil-jdk18on` and `bcprov-jdk18on`; one Build failed in the store, another in the step
  *The pinned GraalVM*, where `exec:exec@machines` found no version of `bcutil-jdk18on` in `[1.80,1.81)`.
- **The range came from porelo-machines and is fixed at its source there.** The exec plugin's dependency
  `porelo-machines` 0.5.2-SNAPSHOT named `sshj` 0.40.0, whose `bcpkix-jdk18on` 1.80 names `bcutil-jdk18on`
  `[1.80,1.81)`, whose pom names `bcprov-jdk18on` `[1.80,1.81)`; the resolver asks Central for their metadata each
  time it meets the range, even though porelo-machines' own 1.85 and 1.85.2 win. Pinning `bcprov` and `bcutil` beside
  it in this pom was measured not to help, since the collector still walks `sshj`'s path; porelo-machines
  0.5.3-SNAPSHOT, which porelo-parent now names, excludes Bouncy Castle from `sshj` and names fixed versions, so this
  repository pins nothing.
- **The cache key misses what changes the dependencies, and a cache is never saved over.** The versions Central was
  asked for come from porelo-parent's snapshot and the BOMs, which `hashFiles('**/pom.xml')` does not see, and
  `actions/cache` saves nothing after an exact hit and nothing at all when the job fails. Built: wherever the local
  Maven repository is cached - the `pinned-jdk` action and the Windows jobs of `build.yml` and `windows.yml` - the
  step is `actions/cache/restore` keyed `maven-<os>-<hashFiles>-<run id>-<attempt>`, restoring
  `maven-<os>-<hashFiles>-` and then `maven-<os>-`, and leaves a marker file right after the restore. Each job ends
  with the `maven-cache-save` action under `if: always()`, which saves under that key only when a jar or pom newer
  than the marker lies in the repository: a job that fetched something keeps it even when it fails, and one that
  fetched nothing saves no copy that would crowd the other caches out of GitHub's limit. The `pinned-jdk` action
  gives its key as `maven-cache-key`, since a composite action has no step at the job's end; each job that uses it
  saves itself. The screen job only restores: the build job of the same run saves under the same key, and only the
  first save of a key is kept.
- No harm test: a workflow's cache and a dependency's version; the measure is a fresh local repository's online
  `validate`, which asks Central for no release's `maven-metadata.xml`, then the same offline, and the Build's log.

## Acceptance

- Two Builds of main in a row with no pom changed: the second one's log has no `Downloading from central` line, and
  no `maven-metadata.xml` of a release is asked of Central.
