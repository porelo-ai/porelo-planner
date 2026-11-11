# PL52 — Every Outside Download Of The Build Comes From A Cache

```json
{
  "$schema": "issue-1.json",
  "id": "PL52",
  "title": "Every Outside Download Of The Build Comes From A Cache",
  "state": "Review",
  "size": "S",
  "priority": "Medium",
  "rank": 26,
  "blockedBy": [],
  "covers": "The Build and Windows workflows take every outside download from a cache keyed by its version or digest, and an entry nobody reads any more goes after a set time, so an outside registry's outage does not turn a build red"
}
```

**Size:** S - the repository's own workflows only, with no contract; a change to the CI alone, so no changelog entry.

**What must be true.** A build of this repository goes green while a registry or archive it downloads from is down,
as long as what it needs was downloaded once before; and a cache entry nobody reads any more goes after a set time.

## Plan

Read in today's workflows:

- `build.yml`: the JDK, the Maven repository and wrapper come through `.github/actions/pinned-jdk`, cached; the Flutter
  SDK and pub packages through `subosito/flutter-action` with `cache: true`. Not cached: `apt-get install rpm`, from
  the runner's Ubuntu archive, on every run.
- `windows.yml`: the runner's own Java runs `./mvnw` with no cache at all, so the wrapper's Maven and every
  dependency are downloaded from Maven Central on every run.
- Every `actions/cache` entry today is keyed by a hash or version and never removed; GitHub only evicts entries
  unused for days or past the repository's limit.

Steps:

1. `windows.yml` caches Maven's local repository and its wrapper's download as the pinned-jdk action does, keyed
   by the poms' and the wrapper properties' hashes.
2. `rpm` is installed from Debian package files kept in a cache keyed by the package's version on the runner's
   image, taken with `apt-get download` once and installed with `dpkg -i`; a miss falls back to the archive.
3. No entry is pruned by a step: GitHub deletes an entry seven days after it was last read, and a key with a
   prefix fallback reads only the newest entry of that prefix, so an older one is no longer read and goes.

A change to the CI alone: the Build and Windows runs after the push are its test, and no step is added only to
block a host.

## Acceptance

- A warm Build and Windows run each pass with the outside hosts blocked; the Build's own run after the push is the
  test, its rpm step taking the packages from the cache on the second run.
