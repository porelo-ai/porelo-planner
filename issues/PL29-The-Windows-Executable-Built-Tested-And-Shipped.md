# PL29 — The Windows Executable Built, Tested And Shipped

```json
{
  "$schema": "issue-1.json",
  "id": "PL29",
  "title": "The Windows Executable Built, Tested And Shipped",
  "state": "Review",
  "size": "L",
  "priority": "Medium",
  "rank": 18,
  "blockedBy": [],
  "covers": "A GraalVM native porelo-planner.exe for Windows x86-64, built and tested on GitHub's Windows runner in the Build, packed as a ZIP and a signed MSIX that puts it on the PATH, with SHA256SUMS and a build provenance attestation, and published beside the Linux packages"
}
```

**Size:** L - a build job, a Maven profile, a signed MSIX and a publish step, plus the native start test on a second
platform; the pinned Windows GraalVM may need the shared build tools first, and the packages' names, places and the
command on the PATH are what the window and its people rely on; it packs with the window's scripts and waits on their
change and the shared build tools'.

**What must be true.** A person on Windows installs an MSIX, or unpacks a ZIP, and the window then finds
`porelo-planner.exe` on the PATH and starts it, with nothing else installed but Git for Windows. The executable is built and tested from the same commit as the Linux packages, and
whether the ZIP is the one this repository's Build made can be checked.

## Plan

- **A Windows job in the Build**, `build.yml/windows`, named `Windows`, on `windows-2025`, with `timeout-minutes: 60`
  and each step's own limit below it. It runs on every push the Build builds and on a dispatch, never on a pull
  request, since the attestation needs the job's token. Its own `permissions:` hold `contents: read` and the
  attestation's two, `id-token: write` and `attestations: write`, as `check-actions` lists them; the workflow's top
  stays `contents: read`. The checkout keeps no credentials and takes LF line ends (`core.autocrlf false`).
  - **The pinned GraalVM for Windows.** `porelo-machines jdk --github --cache <dir>` already installs the Windows zip
    when it runs on Windows, by the digest `jdk --print-sha256` names there, and checks a cached archive against it
    again before it is unpacked; only the archive is cached, under that digest, never the unpacked JDK. The
    `pinned-jdk` action fetches a Linux shfmt, so the Windows job has these steps of its own, sources the action's
    `pinned.sh` for the wrapper's retry, and `pinned.sh` stays a verbatim copy. The wrapper's Maven is cached by its
    exact key alone, without a `restore-keys` prefix.
  - **The build.** `mvnw.cmd -B -s settings.xml -Pnative,windows verify -Dplanner.snapshot.run=<run>`, which runs:
    - the tests tagged `windows`, as the Windows trial runs them; the rest of the unit suite runs on Linux, since it
      was never sorted into what Linux alone can run, and a test tagged `windows` names what only Windows shows;
    - `native-image` with `-march=x86-64`, using the runner's Visual Studio build tools;
    - the CPU check on the `.exe`, which reads the image's own list of required features, whatever the file format;
    - `NativeStartIT` against the `.exe`, each start with `LOCALAPPDATA` set to a directory of its own, never the
      account's; the three starts that need the test's Git server over HTTPS, which is Linux-only, are tagged `linux`
      and excluded there.
  - **The notices** - the runtime classpath, the bill of materials and `THIRD-PARTY-NOTICES` - move from the profile
    `dist` to `native`, so the ZIP and the Linux packages take them from one place, each from the runtime it was
    built with. The shared build tools' `third-party-notices` writes on a file system without POSIX permissions only
    from `0.5.3-SNAPSHOT` on, which the app's pom names for its build steps' `porelo-release` above the parent's
    version until the parent moves to it; the work tools' comparison test keeps the parent's version, since the newer
    build tools' work commands answer otherwise. The Windows notices are not taken from the
    Linux job instead: they name the runtime of the GraalVM the executable was built with, and the Windows job would
    wait for the whole Linux build.
  - **The ZIP.** A new Maven profile `windows` packs `porelo-planner-<version>-windows-x64.zip`, `<version>` as the
    Linux packages carry it (`<x.y.z>~snapshot.<run>` from `main`), into `target/windows`. It holds
    `porelo-planner.exe`, `LICENSE`, `LICENSE-GPL-3.0`, `THIRD-PARTY-NOTICES`, `copyright` and `bom.json`, nothing
    else. `WindowsZipIT` holds it to exactly that list and its `.exe` to the tested one by digest.
  - **The window's scripts, not copies.** The MSIX is packed, signed and install-checked by the window's own
    `tool/windows-package.ps1` and `tool/windows-install-check.ps1`, which the job checks out from the window's
    repository at a pinned full commit of its `main`, named in a comment beside it; nothing of them is kept here.
    They serve another package: the ZIP taken as it is; the name, version and run, the manifest's folder, the
    package's identity and the tested executable given; signing required where it must be; the key never persisted
    outside the block that removes it, whatever its provider; the command started through its alias with a readiness
    check of the caller's; and what the started command writes named, refused before and removed after. What only the
    planner knows - that `planner.json` appears in `%LOCALAPPDATA%\porelo-planner` and answers `/health` with its
    token as `{status, version, commit}`, `UP` with the ZIP's version and the commit the job built - is
    `tool/windows-planner-ready.ps1`, which the install check runs; an answer whose `commit` is null, a build without
    a checkout, is refused there.
  - **The MSIX**: `porelo-planner-<version>-windows-x64.msix`, from the files unpacked out of the ZIP into a fresh
    directory with the manifest and its logos from `planner/app/src/windows`. Identity `ai.porelo.planner`,
    publisher `CN=porelo.ai`, the window's. It declares one app execution alias, `porelo-planner.exe`, a console one,
    and turns write virtualization off (`unvirtualizedResources`), so `planner.json` lands in the account's real
    `%LOCALAPPDATA%\porelo-planner`. After packing, its entries are compared with the ZIP's plus the manifest and the
    logos, its `.exe` with the tested one by digest, and its manifest's alias and version with the expected ones.
    `makeappx.exe` and `signtool.exe` are taken by their full path under the Windows SDK, PowerShell under
    `%SystemRoot%`, never by a bare name. The MSIX takes four numbers, `<major>.<minor>.<patch>.<run>`; a run above
    65535, which an MSIX cannot carry, stops the job. The public certificate goes out beside it as `porelo-msix.cer`,
    the window's name for the same certificate.
  - **The signing.** The certificate the secrets `WINDOWS_SIGNING_PFX` and `WINDOWS_SIGNING_PFX_PASSWORD` hold reaches
    the packing step's environment alone, and only on a push to `main` or a tag `v*`; there a missing secret stops the
    job, named. The key is read from the environment into memory, put in the user's certificate store for the one
    signing, found by its thumbprint, held to the pinned thumbprint, and taken out with its key container whatever
    happens and whatever its provider, before the install check. On a dispatch the MSIX is packed to prove it and not kept.
  - **The install check.** A copy re-signed with a throwaway key must be refused; then the public certificate is
    trusted, the signed MSIX installed, and `porelo-planner.exe` started by another process from an empty working
    directory, after checking that the command resolves to the alias under `%LOCALAPPDATA%\Microsoft\WindowsApps`.
    Its `planner.json` must appear at `%LOCALAPPDATA%\porelo-planner`, where the window reads it, and answer `/health`
    with its token. That directory and `%LOCALAPPDATA%\porelo`, where the planner writes its log beside the window's,
    must not be there before and are removed after; the planner is stopped, the package removed and the certificate
    untrusted whatever happens; anything left is named.
  - **The attestation.** `actions/attest-build-provenance` attests the ZIP and the MSIX, and its Sigstore bundle is
    kept beside them as `<name>.sigstore.json`.
  - **Hand-off.** The files are uploaded as the job's artifact `windows`, `if-no-files-found: error`.
- **Publishing**, a job of its own that needs `build` and `windows`, so a red Windows job never holds back the Linux
  packages. The Build's job exposes its channel and version; the shared `absent` and `put` move from the Linux step into
  `tool/artifactory.sh`, which both source.
  - It refuses an artifact that holds anything but the expected names for this version - the ZIP, the MSIX, the
    certificate, the Sigstore bundle and `SHA256SUMS` - or a link or a directory, and checks `SHA256SUMS`.
  - For a release, every target file is asked for before the first is uploaded, and any that exists stops it. Each
    file goes up with its SHA-1 and SHA-256 to `porelo-dist-windows/porelo-planner/<channel>/<version>/`, a folder of
    its own beside the window's; a snapshot's version carries its run, so no run overwrites another's.
  - It reads each file back without a token, as a person fetches it, and compares its SHA-256 with the uploaded one.
- **Tests, red first.** `NativeStartIT` on Windows fails today, since its runtime directory is made with POSIX
  permissions; that red is the runner's to show. `WindowsZipIT` is seen red here by a ZIP without its notices, packed
  by the profile around a stand-in executable.
- **What the plan reading asked, and why not.**
  - Verifying the attestation with `gh attestation verify` in the publishing job: the artifact is one this run's own
    job made, as the Linux packages are; the job checks it by exact names and digests instead, and the bundle goes out
    for a person's check.
  - Pinning `makeappx.exe`, `signtool.exe` and Visual Studio by digest: they are the runner image's, which changes
    them with each image; they are taken by full path from the image's install directories, which only an
    administrator writes.
  - Signing `porelo-planner.exe` itself: the MSIX's signature covers it through its block map, and the ZIP's is
    checked through `SHA256SUMS` and the attestation.
  - A release's MSIX version below its snapshots': the run number only grows, so the tag's run is higher than any
    snapshot run of the same version before it.
  - Holding the Linux release back while the Windows job is red: a rerun of the failed jobs publishes the Windows
    files of the same run, and holding the Linux packages is what this plan rules out.
  - Checking the tag and the release's dependencies before signing: nothing is published before the `build` job's
    checks have passed, which the publishing job needs.
- **The code reading's findings.**
  - Two runs of one release version at once, such as a tag made again while its first build still runs, can both
    find the Windows files absent and both upload them: the check comes before the upload, which carries no
    condition. It is the Linux publish's exposure too and is left to the storage: a publishing token that cannot
    overwrite closes it, which is under *To be checked*.

## Acceptance

- A Build on `main` ends with the Windows job green. The executable it tested is the one in the ZIP, by digest.
- The ZIP is in `porelo-dist-windows` with its `SHA256SUMS` and Sigstore bundle, fetched without a token.
- `gh attestation verify <zip> --repo porelo-ai/porelo-planner` succeeds.
- On the runner, the installed MSIX's `porelo-planner.exe`, started through the PATH by another process, writes the
  `planner.json` another package's process reads.
- The job names every skipped test.

## To be checked

- That the publishing token cannot overwrite in `porelo-dist-windows`: a second PUT of the same path is refused by
  the storage too.
- Whether `native-image` finds the runner's Visual Studio build tools on its own, or needs `vcvars64.bat` first.
- Whether `porelo-cpu-check` reads a PE executable; otherwise, how the `.exe`'s instruction set is checked.
- **Packaged file system writes.** A packaged app's new files under `AppData` go to a private place of its package,
  which another package's process does not see. The manifest turns write virtualization off (the restricted
  capability `unvirtualizedResources`); the install check shows whether that keeps `planner.json` in the real
  `%LOCALAPPDATA%`. If not, its place is changed with the window in the channel, and only to one the planner's
  ownership checks pass.
- Whether this repository has access to the two signing secrets; the operator checks that before the first run.
- How long the Windows job takes, measured on its first run, so its timeout is set from a measured time.
