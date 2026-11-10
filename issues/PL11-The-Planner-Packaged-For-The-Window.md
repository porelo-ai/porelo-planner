# PL11 — The Planner Packaged For The Window

```json
{
  "$schema": "issue-1.json",
  "id": "PL11",
  "title": "The Planner Packaged For The Window",
  "state": "Review",
  "size": "M",
  "priority": "Medium",
  "rank": 2,
  "blockedBy": ["PL01"],
  "covers": "A .deb and an .rpm with the native executable, published from the Build like the other Porelo packages"
}
```

**Why this size.** a .deb and an .rpm with the native executable, held to their C library floor, and published from the
Build like the other Porelo packages.

**What must be true.** A person installs porelo-planner from the same package repositories as Porelo, and the window
finds and starts it; a push to main publishes a snapshot, a tag a release.

## Plan

- `-Pdist` in `app` as porelo-adapters packs its readers: `jdeb` and the rpm plugin, the executable at
  `/usr/libexec/porelo/planner`, the C library floor measured with `readelf` and checked.
- **The window starts the planner as it needs it**; no user service is installed.
- The Build's publish steps as porelo-adapters' (curl alone, the token on standard input, indexed before believed).
- **Red first:** the linkage check against an executable needing a newer C library than the floor.
- **Third-party notices:** both packages ship the THIRD-PARTY-NOTICES file `porelo-release third-party-notices`
  writes in `prepare-package` from the app's runtime classpath, its CycloneDX bill (made there, since the plugin skips
  a module that is not deployed) and the JDK - as `/usr/share/doc/porelo-planner/` content named in the Debian
  copyright file, and as `%license` in the rpm.

## As built so far

- `dist` in `planner/app/pom.xml`; `src/deb/control` and `src/deb/copyright`; the Build packs, attaches and
  publishes both packages, asks for a release's files before uploading so a published release is never replaced,
  and waits until both the Debian and the RPM index list them. The executable needs GLIBC 2.34 and ZLIB 1.2.2,
  measured with `readelf`.
- Seen red: the linkage check against a glibc floor of 2.32, a zlib floor of 1.2.0 and a ceiling of 2.33. The
  publish step's refusals run locally against the real repository: an `http://` address, no token, two packages, a
  published `.deb` and `.rpm`, a refused token (`401`, said once); the index step with a listed and an unlisted rpm.
- On the ubuntu26.04 and fedora VMs both packages installed, `/usr/libexec/porelo/planner` started and answered `200`
  with its token and `401` without, and both were removed again.
- With `XDG_RUNTIME_DIR` unset the planner now uses `/run/user/<uid>`, as the window reads it.
- `THIRD-PARTY-NOTICES` in both packages, from `porelo-release third-party-notices`; the deb's file is byte for byte
  the generated one.

## Code reading

- The plan was not read before building, though the issue is M; the reading of plan and code was made together
  afterwards (agy, Gemini): four blocking findings and three notes.
- Fixed: the release check's effective pom left out the `screen` module; a rerun could replace a release whose
  `.deb` was stored but not yet indexed, and the RPM index was never waited for; the modules inherited the
  parent's GPL license instead of the planner's LGPL; a refused upload was tried six times and never said; the
  version was read by indentation.
- Moved to the shared generator: the third-party notices (libraries embedded in Spring and Jackson, the full texts
  an rpm system lacks). Built on it: the dist profile writes the classpath, the bill and the notices in
  `prepare-package`, and both packages carry the file (39 jars and the runtime, 1.08 MB, mode 0644). Read on its own
  (agy): no finding. Seen to stop the build: a porelo-release that lacks the command (exit 2).
- The fallback to `/run/user/<uid>` was read on its own (agy): one blocking finding and two notes. Fixed, with
  tests seen red first: a runtime directory given in another form (`link/.`, `x/../x`), reached through a link
  anywhere on its way, or under a directory others can write without the sticky bit is refused, since each lets
  another account swap the place between the check and the write; the temporary file is written through the channel
  that made it. A lock file reached by a second path is refused with them. Declined: a `umask` that takes the
  owner's own bits - a broken environment of the person's own.
- Declined: a tag on a commit not on `main` - tags are the operator's own, and the planner protects nobody from
  their own rights. Packages left in `target/` by a failed local build - the Build checks out clean, and its publish
  step refuses more than one package.

## Acceptance

- The packages install on the local Ubuntu and Fedora VMs, and the installed executable starts.
