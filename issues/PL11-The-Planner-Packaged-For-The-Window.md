# PL11 — The Planner Packaged For The Window

```json
{
  "$schema": "issue-1.json",
  "id": "PL11",
  "title": "The Planner Packaged For The Window",
  "state": "Implementing",
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

## Acceptance

- The packages install on the local Ubuntu and Fedora VMs, and the installed executable starts.
