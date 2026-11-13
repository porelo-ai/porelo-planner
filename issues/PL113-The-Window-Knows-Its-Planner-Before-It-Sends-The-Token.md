# PL113 — The Window Knows Its Planner Before It Sends The Token

```json
{
  "$schema": "issue-1.json",
  "id": "PL113",
  "title": "The Window Knows Its Planner Before It Sends The Token",
  "state": "Implementing",
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
- **The design, decided.** The token is never sent. Each request carries, in its own header instead of the bearer,
  an HMAC-SHA256 keyed with the token's UTF-8 bytes over a canonical form: the ASCII label `porelo-planner-request-1`
  and a zero byte, then length-prefixed fields - the method, the raw request target as sent (`getRequestURI`, `?` and
  the raw query, undecoded, in its order), the `Host` header, the `Content-Type` (empty when absent), the SHA-256 of the
  body bytes as read (an empty and an absent body alike), a UTC time in seconds and a nonce of 16 random bytes. The
  planner refuses a signature more than 60 seconds from its clock or a nonce it has seen within that window (a cache
  pruned to the window), so a caught request cannot be replayed within one run; across runs the token is new at every
  start. More than one signature header, an unknown label or a bearer is refused. Every refusal answers one 401 with an
  empty body and one log line, the comparison in constant time (`MessageDigest.isEqual`).
- **Dispatches and bodies.** The filter verifies only the `REQUEST` dispatch and marks the request with an attribute
  no caller can set; an `ASYNC` or `ERROR` dispatch passes only with that mark, so the changes stream and an error
  status neither re-verify nor bypass. The filter reads the body before it verifies, capped at the largest body the
  contract allows, and answers 401 beyond it, never buffering an unbounded or chunked body.
- **Answers that prove themselves.** Every answer to a signed request carries an HMAC under the token over the label
  `porelo-planner-answer-1`, the request's nonce, the status and the SHA-256 of the answer's body; the screen refuses an
  answer without a matching one, so a process on a freed port can neither read the token nor feed the screen a forged
  board or result. The changes stream's opening answer carries it over the status and the nonce; its events stay
  unproven, which the screen accepts because a reopen proves the planner again.
- **The proof.** `POST /proof`, needing no signature (`security: []` in the contract): the body `{"challenge": "<43
  base64url characters of 32 random bytes>"}`, the answer `{"proof": "<base64url HMAC-SHA256 under the token over the
  ASCII label porelo-planner-proof-1, one zero byte, the planner's port as two bytes and the 32 challenge bytes>"}`.
  The screen calls it at its start, after every connection error, timeout, stream reopen, 401 to a signed request and
  change of the address file's port or token, re-reading the address file first, and compares in constant time. Any
  answer that is not a matching proof - another status, shape, size or a redirect - sends no further request and is
  shown as not the account's planner or an older one, after a back-off capped at 30 seconds and reset when the address
  file changes; the proof has a timeout of its own, longer than a request's, so a planner still starting is not taken
  for a stranger.
- **Bounds.** The proof's exemption from the access filter is exactly `POST` on the raw servlet path `/proof`; its
  body is capped at about 100 bytes, must be `application/json` with that one key, and takes only the canonical
  base64url form. Its refusals answer 400 with an empty body through the filter's own path and log nothing. The mock
  mode computes the real proof, signature and answer proof under its own token, with no flag, fixed token or skip that
  lets an unsigned call in.
- **The window's call.** `porelo_planning` exports one call beside `PlannerAddress` that reads the address, proves the
  planner, and builds, signs and sends a request in one function, so the signed string and the request line cannot
  differ; it throws one named error for a listener that is not the account's planner, not transient, with a fixed
  message for the person - that the planner answering is not this account's planner or an older one, and that opening
  Planning again starts the account's own - and, for the log alone, whether a proof failed or a signature was refused
  and the address file's port, never the token or a signature. The window's `handIdea` and
  `tellPlannerLevel` use it for every request, the `PUT` after NoSuchProject included. The planner refuses a bearer,
  so the window and the planner change together, agreed in the channel.
- **Contract.** The global security requirement, the scheme, the `Unauthorized` response and its `WWW-Authenticate`
  value, the description's header text and the filter's own header change with the scheme.
- **Tests, red first.**
  - Unit, app: the proof's answer for the planner's token and port; a challenge of another length, a non-canonical
    form, an extra key and an oversize body refused; `/proof/`, `/proof;a=b`, `//proof`, `/%70roof`, `GET /proof` and
    `OPTIONS /proof` not exempt; a request with a bearer, without a signature, with two, or with a signature over
    another method, target, host, content type, body or time refused; a replayed nonce refused; a proof answer taken
    as a signature refused; the changes stream and an error status answered after one verification; an oversize body
    refused unread.
  - Unit, screen, over fake listeners: a listener that cannot prove receives no request, and one that proves once and
    is then replaced by one that cannot receives nothing after the change; an answer without its proof is refused.
    Harm: the planner's token, a project's name or an idea given to another account's process, or a forged board
    shown to the person.

## Acceptance

- A process on the planner's old port that does not hold the token learns no token, before or after a reconnect; the
  account's planner is reached as today.
- Each new guard is seen red by a break that compiles.

## To be checked

- Whether the planner's listener on Windows binds its port exclusively, so another account cannot bind a live
  planner's port; the JDK's exclusive bind and the connector's address reuse are read and measured on Windows.
