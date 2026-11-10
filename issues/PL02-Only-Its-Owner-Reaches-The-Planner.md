# PL02 — Only Its Owner Reaches The Planner

**Status:** Implementing; blocked by [PL01](README.md#pl01).
**Size:** M - a trust boundary: the binding, a token readable only by its owner, its check on every call and SSE,
and the way the window finds both.

**What must be true.** The planner answers only the account that started it: it listens on the loopback address
alone, and every call - SSE included - carries a token that only that account can read; a call without it, with a
wrong one, or from another account on the same computer is refused before anything is read or written.

## Plan

- **The address file**, `$XDG_RUNTIME_DIR/porelo-planner/planner.json`, holds `{"port": <n>, "token": "<43
  base64url characters>"}`. It is a contract with the window, written in `doc/index.md` and, once it exists, the
  OpenAPI description's introduction. One planner per account serves every project.
  - **The runtime directory:** the property `planner.runtime-directory`, by default `${XDG_RUNTIME_DIR}`; unset, empty
    or not absolute refuses to start. It must itself be a real directory (read with `NOFOLLOW_LINKS`), owned by the
    planner's own account and `0700`, as the system makes it; else the planner refuses to start, naming it. The same
    holds for `porelo-planner/` in it, which the planner makes `0700` when missing.
  - **One planner at a time:** the planner holds an exclusive lock (`FileChannel.tryLock`) on
    `porelo-planner/planner.lock` (created `0600`, never followed through a link) for as long as it runs. Without the
    lock it refuses to start, saying a planner already runs. With it, any `planner.json` there is a dead planner's and
    is replaced. The kernel lets the lock go when a planner dies; a reused process id cannot fake it.
  - **Writing:** only once the server listens (`WebServerInitializedEvent`), so a window that reads the file can call
    at once. The port is the system's choice (`server.port=0`) unless one is configured. The file is written to a
    temporary file of a unique name in that directory, created `0600` with `CREATE_NEW`, then moved over
    `planner.json` with `ATOMIC_MOVE` under the lock; the temporary file is deleted in a `finally`.
  - **On stop** it deletes `planner.json` while it still holds the lock.
- **The token** is 32 bytes of `SecureRandom`, base64url without padding, made at run time when the application
  starts - never in a static field native-image could initialize at build time - and never logged, printed or put on
  a command line.
- **`AccessFilter`**, a servlet filter in `app` ahead of every other, on every path and every dispatch (`REQUEST`,
  `ASYNC`, `ERROR`) - `/health`, the OpenAPI description and SSE included: `Authorization: Bearer <token>` compared
  with `MessageDigest.isEqual(expected, given)`, the planner's own bytes first; anything else gets status `401` set
  directly (never `sendError`, which would reach Spring's error page), `WWW-Authenticate: Bearer` and an empty body,
  before any controller runs. No CORS header is sent, so a web page in a browser on the same computer cannot read
  an answer.
- **The binding:** a `WebServerFactoryCustomizer` checks, before the server binds, that `server.address` is set and
  `isLoopbackAddress()`; an empty or other address refuses to start, so the server never listens elsewhere, even
  briefly.
- **Tests run apart:** every `@SpringBootTest` sets `planner.runtime-directory` to a `0700` temporary directory of its
  own; `NativeStartIT` sets `XDG_RUNTIME_DIR` to one for the executable. Calls in tests read the token from the
  address file.
- **Red first:**
  - `AccessFilterTest` (`@SpringBootTest`): no header, a wrong token, a token of the right length with other bytes,
    `Basic` instead of `Bearer`, and an event stream request without the token each get `401`, `WWW-Authenticate:
    Bearer` and an empty body; the right token reaches `/health`.
  - `AddressFileTest` (plain unit test over a temporary directory): the file is `0600` and its directory `0700`; a
    runtime directory or `porelo-planner/` that is a link, of another mode, or missing refuses; a second planner
    while the first holds the lock refuses; a dead planner's file is replaced; stop deletes the file; no temporary
    file is left.
  - `BindingTest`: `server.address` empty and `0.0.0.0` each refuse to start.
  - `NativeStartIT` starts the executable without a port, finds it through its address file, calls `/health` with
    and without the token, and sees two starts write different tokens.

## Plan read before building

- agy (Gemini, read-only) read the first plan: four blocking findings and three notes, all taken above - the
  runtime directory itself checked, a unique temporary file deleted after the move, a kernel lock instead of a
  process id, the address checked before binding, the filter on every dispatch without `sendError`, the token made
  at run time, and tests apart from each other and from a running planner.

## Acceptance

- The refusals above, seen red, then green; the native executable started without a port, found through its address
  file, answers with the token and refuses without it.
