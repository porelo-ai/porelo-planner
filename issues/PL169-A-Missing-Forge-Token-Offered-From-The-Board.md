# PL169 — A Missing Forge Token Offered From the Board

```json
{
  "$schema": "issue-1.json",
  "id": "PL169",
  "title": "A Missing Forge Token Offered From the Board",
  "state": "Analyzed",
  "size": "M",
  "priority": "High",
  "rank": 85,
  "blockedBy": ["PL165"],
  "covers": "A GitHub board that cannot be read because the vault holds no token for the address, or GitHub refuses it for a missing right, is refused with NoForgeAccess naming the address and the right, and the planning screen offers the window's handOnForgeToken beside it and reads the board again once the token is in the vault"
}
```

**Why this size.** M, not S: small in code, but one contract change the window builds against - a new refusal in
`planner.yaml` and a new call in the screen's `PlanningHost` - with its documentation and a widget test.

**What must be true.** A person whose token is missing or lacks a right is told which address and which right, and
can hand the token on from the board without leaving it.

## Plan

- Today a refused login is `StoreException.CredentialRefused`, which the app answers as the store's refusal
  (`planner.yaml`); the screen shows it as text. `PlanningHost` (`screen/lib/src/hooks.dart`) is what the window
  implements for the screen.
- `planner.yaml`: a new refusal code `NoForgeAccess` (HTTP 403) with fields `address` and, where GitHub names it,
  `right` (such as `Issues` or `Projects`). The GitHub store ([PL165](README.md#pl165)) raises it for no token in
  the vault and for a 401 or a 403; the right comes from the forge clients' `acceptedPermissions()` of the refusal.
- The screen: `PlanningHost` gains `Future<bool> handOnForgeToken(String address)`; the window puts the person's
  token into this computer's vault and answers whether it did. The screen offers it beside a `NoForgeAccess`
  refusal and reads the board again after `true`.
- Red first: a store test where a 403 naming `Projects` gives `NoForgeAccess` with that right; a controller test
  of the answer's code and fields; a widget test that shows the offer for `NoForgeAccess`, none for another
  refusal, and a refresh after `true`.

## Acceptance

- In the test organization, a token without Projects rights gives the board's refusal naming `Projects`, and the
  offer stands beside it.
- The window's pin can be raised to the commit that carries both the refusal and the call.
