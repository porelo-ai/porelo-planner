# PL113 — The Window Knows Its Planner Before It Sends The Token

```json
{
  "$schema": "issue-1.json",
  "id": "PL113",
  "title": "The Window Knows Its Planner Before It Sends The Token",
  "state": "Backlog",
  "size": "L",
  "priority": "Medium",
  "rank": 49,
  "blockedBy": [],
  "covers": "The window never sends the planner's token: each request carries a keyed hash under it, and before its first request and after any connection error the screen has the listener at the address file's port prove it holds the token, so a process that took a freed port learns no token and is found at once"
}
```

**Size:** L - the security scheme of every operation in the contract, the app's access filter, a new proof operation,
the screen's source and one exported call for the window, on Linux and Windows; the window's two own calls change in
the window's repository after agreement in the channel.

**What must be true.** The window gives the planner's token to nobody, and a project's name or a request's body only
to the account's own planner. Today the screen reads the address and the token from the planner's address file and
sends the token as a bearer with every call and every reconnect of the changes stream. A planner that ended leaves its
address file behind, and another account's process that binds the freed port on the loopback address receives the
token, the project's name and the request with the next call.

## Plan

- **Today, read in the code.** Every operation in `planner.yaml` needs the bearer token, checked in `AccessFilter`
  (registered for request, async and error dispatches); a missing or wrong one answers 401 with an empty body. The
  screen's `HttpPlanningSource` (`screen/lib/src/http/planner_source.dart`) is built once from one address and sends
  the token with every request and stream reconnect. The window calls the planner without the source in two places,
  `handIdea` (`POST /projects/<name>/ideas`, and `PUT /projects/<name>` on NoSuchProject) and `tellPlannerLevel`
  (`PUT /log/level`).
- **The design, decided.** The token is never sent. Each request carries an HMAC-SHA256 under the token over the
  method, the path with its query, a digest of the body and a fresh random nonce, in its own header instead of the
  bearer; the planner checks it in constant time. The token is new at every start of the planner, so a request a
  process on a dead planner's port catches is worth nothing to it.
- **The proof.** `POST /proof`, needing no signature: the body `{"challenge": "<43 base64url characters of 32 random
  bytes>"}`, the answer `{"proof": "<base64url HMAC-SHA256 under the token over the ASCII label porelo-planner-proof-1,
  one zero byte and the 32 challenge bytes>"}`. The screen calls it at its start and after every connection error,
  timeout or stream reopen, re-reading the address file first, and compares in constant time. Any answer that is not a
  matching proof - another status, shape, size, a redirect or a timeout - sends no further request and is shown as not
  the account's planner, after a back-off; a planner without the operation is such an answer. The exposure that stays:
  in the race between a proof and the next request, a process that took the port sees that one request's path and
  body, never the token.
- **Bounds.** The proof's exemption from the access filter is exactly `POST` on the raw servlet path `/proof`; its
  body is capped at about 100 bytes, must be `application/json` with that one key, and takes only the canonical
  base64url form. Its refusals answer 400 with an empty body through the filter's own path and log nothing. The mock
  mode computes the real proof and signature under its own token.
- **The window's call.** `porelo_planning` exports one call beside `PlannerAddress` that reads the address, proves the
  planner and signs requests, and throws one named error for a listener that is not the account's planner, so the
  window copies no HMAC.
- **Tests, red first.**
  - Unit, app: the proof's answer for the planner's token; a challenge of another length, a non-canonical form, an
    extra key and an oversize body refused; `/proof/`, `/proof;a=b`, `//proof`, `/%70roof` and `GET /proof` not
    exempt; a request with a bearer, without a signature, with a signature over another path, body or method refused.
  - Unit, screen, over fake listeners: a listener that cannot prove receives no request, and one that proves once and
    is then replaced by one that cannot receives nothing after the change. Harm: the planner's token, a project's name
    or an idea given to another account's process.

## Acceptance

- A process on the planner's old port that does not hold the token learns no token, before or after a reconnect; the
  account's planner is reached as today.
- Each new guard is seen red by a break that compiles.

## To be checked

- Whether the planner's listener on Windows binds its port exclusively, so another account cannot bind a live
  planner's port; the JDK's exclusive bind and the connector's address reuse are read and measured on Windows.
