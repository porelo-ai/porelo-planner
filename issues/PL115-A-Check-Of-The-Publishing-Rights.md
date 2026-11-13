# PL115 — A Check Of The Publishing Rights

```json
{
  "$schema": "issue-1.json",
  "id": "PL115",
  "title": "A Check Of The Publishing Rights",
  "state": "Implementing",
  "size": "XS",
  "priority": "High",
  "rank": 51,
  "blockedBy": [],
  "covers": "A workflow started by hand shows, line by line with the HTTP status, what anyone may read and what the Build's publishing token may read, upload and delete in Artifactory's porelo-files and files, so a publish that fails on a right is told from one that fails on the code"
}
```

**Size:** XS - one workflow file started by hand, no code and no contract.

**Estimate:** 17 min, the measured median of XS.

**What must be true.** Whoever sets the publishing rights can start one workflow and read, for `porelo-files` and
`files` each, whether a file can be read without a login and whether `JF_ACCESS_TOKEN` can read, upload and delete
one. Today the Build's publish step stops at its first refusal, and which right is missing is not visible from it.

## Plan

- `.github/workflows/jfrog-access.yml`, `workflow_dispatch` only, no permissions for the run's own token. For each
  repository it asks for a probe path under `porelo-planner/access-check/` named by the run and its attempt: absent
  anonymously and with the token, uploaded with the token, read anonymously (a redirect followed, as the absence
  check and the pins read) and with the token (no redirect followed, so the token stays with the host), deleted with
  the token, and absent again anonymously. Each line says PASS or FAIL with what was wanted and the status; the run
  fails only at the end, and a probe it could not delete is named.
- The token reaches curl as config on standard input, never on a command line or in the log.
- Tests: none at unit level, since nothing is built; the script was run once against an unreachable address to see
  every line fail with its status and the run end non-zero. Its real test is the first run by hand.

## Acceptance

- A run by hand prints fourteen lines, seven per repository, each with its status, and ends red only when one of them
  failed.
