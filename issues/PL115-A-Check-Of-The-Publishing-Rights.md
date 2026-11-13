# PL115 — A Check Of The Publishing Rights

```json
{
  "$schema": "issue-1.json",
  "id": "PL115",
  "title": "A Check Of The Publishing Rights",
  "state": "Review",
  "size": "XS",
  "priority": "High",
  "rank": 51,
  "blockedBy": [],
  "covers": "A workflow started by hand shows, one pass or fail line each, whether every secret and variable the Build's publish and signing steps use is set, what anyone may read and the publishing token may read, upload and delete in Artifactory's porelo-files, where the Build publishes, and whether the Windows signing key opens and is the pinned signer, so a publish that fails on a right or a key is told from one that fails on the code"
}
```

**Size:** XS - one workflow file started by hand, no code and no contract.

**Estimate:** 17 min, the measured median of XS.

**What must be true.** Whoever sets the publishing rights and keys can start one workflow and read whether each
precondition of the Build's publish and signing steps holds: every secret and variable set, a file in `porelo-files`
readable without a login and readable, uploadable and deletable with `JF_ACCESS_TOKEN`, and the Windows
signing key opening with its password as the pinned signer. Today the Build stops at its first refusal, and which
precondition is missing is not visible from it.

## Plan

- `.github/workflows/publishing-preconditions.yml`, `workflow_dispatch` only, no permissions for the run's own token.
- Job one, on Linux: a line per secret and variable the Build reads (`JF_URL`, `JF_ACCESS_TOKEN`,
  `WINDOWS_SIGNING_PFX`, `WINDOWS_SIGNING_PFX_PASSWORD`), by name and set or empty, never the value. Then, in
  `porelo-files` it asks for a probe path under `porelo-planner/access-check/` named by the run and its attempt: absent
  anonymously and with the token, uploaded with the token, read anonymously (a redirect followed, as the absence
  check and the pins read) and with the token (no redirect followed, so the token stays with the host), deleted with
  the token, and absent again anonymously. Each line says PASS or FAIL with what was wanted and the status; the run
  fails only at the end, and a probe it could not delete is named.
- The token reaches curl as config on standard input, never on a command line or in the log.
- Job two, on the Windows runner the pack step signs on: the PFX decodes from base64 and opens with its password as
  an ephemeral key set, written to no file and no certificate store, so nothing is left to delete; it holds a private
  key, its subject is the manifest's `Publisher`, its thumbprint the packing script's pinned signer, and it is valid
  now. Only the subject, the thumbprint and the expiry are printed. The packages carry no other signing key.
- Tests: none at unit level, since nothing is built. The Linux script ran once here against an unreachable address,
  with the token empty and set, and each line failed or passed with its status. The Windows step has not run, since
  no PowerShell is here. The first run by hand is the real test of both.

## Acceptance

- A run by hand prints a line per secret and variable, seven for `porelo-files` and six for the signing key, each with
  what it got, and a job ends red only when one of its lines failed.
