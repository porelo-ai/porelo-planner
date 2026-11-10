# PL02 — Only Its Owner Reaches The Planner

**Status:** Analyzed; blocked by [PL01](README.md#pl01).
**Size:** M - a trust boundary: the binding, a token readable only by its owner, its check on every call and SSE,
and the way the window finds both.

**What must be true.** The planner answers only the account that started it: it listens on the loopback address
alone, and every call - SSE included - carries a token that only that account can read; a call without it, with a
wrong one, or from another account on the same computer is refused before anything is read or written.

## Plan

- On start the planner writes a random 256-bit token and its port to a file in `$XDG_RUNTIME_DIR/porelo-planner/`
  (mode `0600`, directory `0700`, created by `link(2)` so a second planner does not overwrite the first's), and
  deletes it on stop. It binds `127.0.0.1` on a port the system chooses unless one is configured.
- A servlet filter in `app` compares `Authorization: Bearer <token>` in constant time, for every path, the OpenAPI
  description too; a refusal says only `401`.
- The window reads the file to find port and token. The file's place is a contract with the window and is written
  in the OpenAPI description's introduction.
- **Red first:** `LocalAccessTest` - no token, a wrong one, a token of the right length but other bytes, and an
  SSE subscription without one are refused; `TokenFileTest` - the file is `0600`, refused when its directory is
  someone else's or a link.

## Acceptance

- The four refusals above, seen red, then green; a native test calling with and without the token.

## Open questions

- A Unix socket instead of loopback plus token would leave the token out: the socket's file mode does the check. It
  needs the window's HTTP client to speak over a Unix socket. Loopback plus token is planned; which does the
  operator want?
