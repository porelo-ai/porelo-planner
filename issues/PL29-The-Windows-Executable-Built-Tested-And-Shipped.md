# PL29 — The Windows Executable Built, Tested And Shipped

```json
{
  "$schema": "issue-1.json",
  "id": "PL29",
  "title": "The Windows Executable Built, Tested And Shipped",
  "state": "Implementing",
  "size": "L",
  "priority": "Medium",
  "rank": 18,
  "blockedBy": [],
  "covers": "A GraalVM native porelo-planner.exe for Windows x86-64, built and tested on GitHub's Windows runner in the Build, packed as a ZIP and a signed MSIX that puts it on the PATH, with SHA256SUMS and a build provenance attestation, and published beside the Linux packages"
}
```

**Size:** L - a build job, a Maven profile, a signed MSIX and a publish step, plus the native start test on a second
platform; the pinned Windows GraalVM may need the shared build tools first, and the packages' names, places and the
command on the PATH are what the window and its people rely on.

**What must be true.** A person on Windows installs an MSIX, or unpacks a ZIP, and the window then finds
`porelo-planner.exe` on the PATH and starts it, with nothing else installed but Git for Windows. The executable is built and tested from the same commit as the Linux packages, and
whether the ZIP is the one this repository's Build made can be checked.

## Plan

- **A Windows job in the Build**, named `Windows`, on `windows-2025`, with `timeout-minutes: 60` and each step's own
  limit below it. It runs where the Build publishes and never on a pull request, since the attestation needs the
  job's token.
  - **The pinned GraalVM for Windows.** A Windows GraalVM is fetched by the digest the shared tools name, checked, and
    unpacked. `porelo-machines jdk` pins only the Linux archive today, so the shared build tools must first name the
    Windows archive and its digest; this issue waits on that change before its job can build. The `pinned-jdk`
    action fetches a Linux shfmt, so the Windows job gets its own step, and `pinned.sh` stays a verbatim copy.
  - **The build.** `mvnw.cmd -B -s settings.xml -Pnative,windows verify`, which runs:
    - the unit and integration tests without those tagged `linux` or `documents`; a skipped test is named;
    - `native-image` with `-march=x86-64`, using the runner's Visual Studio build tools;
    - the CPU check on the `.exe`;
    - `NativeStartIT` against the `.exe`, with `LOCALAPPDATA` set to a directory of its own.
  - **The ZIP.** A new Maven profile `windows` packs `porelo-planner-<version>-windows-x64.zip`. It holds
    `porelo-planner.exe`, the license and the notices the Linux packages carry, and the bill of materials.
    `SHA256SUMS` goes beside it.
  - **The MSIX.** `porelo-planner-<version>-windows-x64.msix` holds the same files and declares the app execution
    alias `porelo-planner.exe`, which puts the command on the PATH. It is signed with the certificate the secrets
    `WINDOWS_SIGNING_PFX` and `WINDOWS_SIGNING_PFX_PASSWORD` hold, which never reach a file or a command line, as the
    window's MSIX is. The MSIX takes four numbers, `<major>.<minor>.<patch>.<run>`. Off `main`, or without the secrets,
    it is packed to prove it and not kept.
  - **The install check.** The signed MSIX is installed on the runner, `porelo-planner.exe` is started through the
    alias from another process, and its `planner.json` must appear at the path the window reads (see below).
  - **The attestation.** `actions/attest-build-provenance` attests the ZIP and the MSIX, and its Sigstore bundle is kept beside the
    ZIP as `<name>.sigstore.json`. The job gets the attestation's two permissions alone, as `check-actions` lists them.
  - **Hand-off.** The files are uploaded as the job's artifact `windows`, `if-no-files-found: error`.
- **Publishing.** The publish job downloads `windows` only when the Windows job succeeded, so a red Windows job never
  holds back the Linux packages.
  - It checks `SHA256SUMS`, refuses a release whose folder already exists, and uploads each file with its SHA-1 and
  SHA-256 to `porelo-dist-windows/porelo-planner/<channel>/<version>/`, a folder of its own beside the window's.
  - It reads each file back without a token, as a person fetches it.
- **Tests, red first.** `NativeStartIT` on Windows fails today, since the executable looks for `XDG_RUNTIME_DIR`.
  The ZIP check, which unpacks the ZIP and compares its entries with the expected list, is seen red by a missing
  notices file.

## Acceptance

- A Build on `main` ends with the Windows job green. The executable it tested is the one in the ZIP, by digest.
- The ZIP is in `porelo-dist-windows` with its `SHA256SUMS` and Sigstore bundle, fetched without a token.
- `gh attestation verify <zip> --repo porelo-ai/porelo-planner` succeeds.
- On the runner, the installed MSIX's `porelo-planner.exe`, started through the PATH by another process, writes the
  `planner.json` another package's process reads.
- The job names every skipped test.

## To be checked

- Whether `native-image` finds the runner's Visual Studio build tools on its own, or needs `vcvars64.bat` first.
- Whether `porelo-cpu-check` reads a PE executable; otherwise, how the `.exe`'s instruction set is checked.
- **Packaged file system writes.** A packaged app's new files under `AppData` go to a private place of its package,
  which another package's process does not see. Either the manifest turns write virtualization off (the restricted
  capability `unvirtualizedResources`), or the address file lives outside `AppData`. The install check decides, and
  the address file's place is changed with the window in the channel if it must move.
- Whether this repository has access to the two signing secrets; the operator checks that before the first run.
- How long the Windows job takes, measured on its first run, so its timeout is set from a measured time.
