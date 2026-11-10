# PL02 — Only Its Owner Reaches The Planner

**Status:** Review; blocked by [PL01](README.md#pl01).
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

## As built

- `AccessToken`, `AddressFile`, `AccessFilter`, `LoopbackBinding` and their wiring in `AccessConfiguration`, in `app`;
  `server.port=0` by default. The filter covers every path, so the event stream gets the check when it is added.
- A refused second start in the same process never opens a channel on the lock file: closing any channel on it lets
  go of the process's lock, which a test in a second process showed. A dead planner's `planner.json` is deleted as
  soon as the lock is held, not only when the new one listens.
- The address-file contract is in `doc/index.md`.

## Code reading

- agy (Gemini, read-only) read the change: three blocking findings and four notes.
- Fixed, each with a test seen red first: the lock let go by a refused start in the same process; a dead planner's
  file readable until the server listens; the token's text form (a test now guards that it hides the token).
- Fixed without a test, since they do no harm: two starts racing to make `porelo-planner/` now both reach the lock
  instead of one failing with the wrong reason; the lock's channel is closed when its owner check fails.
- Declined: a check that `planner.lock` is a regular file - only the account itself can place anything in its
  `0700` directory, and a planner that hangs on its own named pipe harms no one else. Deleting a crashed start's
  temporary file - it holds the token of a dead planner, which opens nothing, readable by the account alone. A test
  of the `ASYNC` and `ERROR` dispatches - no call without the token reaches either, since the `REQUEST` dispatch
  refuses it first; their registration stays as a second line. The test runtime directories deleted before the
  cached test contexts close - the late close finds nothing to delete and does no harm.

## Acceptance

- The refusals above, seen red, then green; the native executable started without a port, found through its address
  file, answers with the token and refuses without it.
