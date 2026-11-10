# PL02 — Only Its Owner Reaches The Planner

**Status:** Implementing; blocked by [PL01](README.md#pl01).
**Size:** M - a trust boundary: the binding, a token readable only by its owner, its check on every call and SSE,
and the way the window finds both.

**What must be true.** The planner answers only the account that started it: it listens on the loopback address
alone, and every call - SSE included - carries a token that only that account can read; a call without it, with a
wrong one, or from another account on the same computer is refused before anything is read or written.

## Plan

- **The address file**, `$XDG_RUNTIME_DIR/porelo-planner/planner.json`, holds `{"port": <n>, "token": "<43
  base64url characters>", "pid": <n>}`. It is a contract with the window, written in `doc/index.md` and, once it
  exists, the OpenAPI description's introduction. One planner per account serves every project.
  - The planner writes it only once its server listens (Spring's `WebServerInitializedEvent`), so a window that
    reads it can call at once. The port is the system's choice (`server.port=0`) unless one is configured.
  - The directory is made `0700`. An existing one is taken only when it is a directory, not a link, owned by the
    planner's own account and `0700`; else the planner refuses to start, naming it. `XDG_RUNTIME_DIR` unset, empty or
    not absolute refuses too: there is no other place only the account can read.
  - The file is written to a temporary name in that directory, created `0600`, then published by `Files.createLink`
    (`link(2)`), which fails when `planner.json` exists, so a second planner never overwrites a first. On that
    failure the planner reads the existing file: when its `pid` is a live process, it refuses to start, saying the
    planner already runs; when not, the file is left from a planner that died, and is replaced once.
  - On stop it deletes the file only when it still holds its own token.
- **The token** is 32 bytes of `SecureRandom`, base64url without padding, made at each start and never logged,
  printed or put on a command line.
- **`AccessFilter`**, a servlet filter in `app` ahead of every other, on every path and dispatch - `/health`, the
  OpenAPI description and SSE included: `Authorization: Bearer <token>` compared with `MessageDigest.isEqual`;
  anything else answers `401` with `WWW-Authenticate: Bearer` and no body, before any controller runs. No CORS
  header is sent, so a web page in a browser on the same computer cannot read an answer.
- **The binding** stays `127.0.0.1` from `PL01`; `server.address` cannot be set to anything but a loopback address
  (the planner refuses to start otherwise).
- **Red first:**
  - `AccessFilterTest` (`@SpringBootTest`): no header, a wrong token, a token of the right length with other bytes,
    `Basic` instead of `Bearer`, and an event stream request without the token are each refused `401`; the right
    token reaches `/health`.
  - `AddressFileTest` (plain unit test over a temporary directory): the file is `0600` and its directory `0700`; a
    directory that is a link, another's mode or missing `XDG_RUNTIME_DIR` refuses; a second start with a live `pid`
    refuses; a file of a dead `pid` is replaced; stop deletes only its own file.
  - `NativeStartIT` reads the address file and calls `/health` with and without the token.

## Acceptance

- The refusals above, seen red, then green; the native executable started without a port, found through its address
  file, answers with the token and refuses without it.
