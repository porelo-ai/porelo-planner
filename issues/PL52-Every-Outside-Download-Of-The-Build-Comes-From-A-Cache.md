# PL52 — Every Outside Download Of The Build Comes From A Cache

```json
{
  "$schema": "issue-1.json",
  "id": "PL52",
  "title": "Every Outside Download Of The Build Comes From A Cache",
  "state": "Idea",
  "size": "S",
  "priority": "Medium",
  "rank": 26,
  "blockedBy": [],
  "covers": "The Build and Windows workflows take every outside download from a cache keyed by its version or digest, and keep only the newest entry of each, so an outside registry's outage does not turn a build red"
}
```

**Size:** S - the repository's own workflows only, with no contract; a change to the CI alone, so no changelog entry.

**What must be true.** A build of this repository goes green while a registry or archive it downloads from is down,
as long as what it needs was downloaded once before; and the caches never grow past the newest entry of each.

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
3. After a run on `main` saves a new entry, a step deletes the older entries of the same key prefix
   (`gh cache delete`, with `actions: write` for that step's job alone), so only the newest of each stays.

The check that goes red first: a Windows run with Maven Central blocked in the job (`127.0.0.1 repo.maven.apache.org`
in the hosts file) after one warm run, red today, green after.

## Acceptance

- A warm Build and Windows run each pass with the outside hosts blocked; the repository's cache list shows one entry
  per key prefix after a run on `main`.

## To be checked

- Whether the repository's Actions settings can set a retention time for cache entries, which would replace step 3.
- Whether `subosito/flutter-action`'s cache keeps older SDK entries, and the same pruning covers them.
