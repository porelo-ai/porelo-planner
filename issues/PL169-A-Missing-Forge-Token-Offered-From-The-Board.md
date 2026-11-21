# PL169 — A Missing Forge Token Offered From the Board

```json
{
  "$schema": "issue-1.json",
  "id": "PL169",
  "title": "A Missing Forge Token Offered From the Board",
  "state": "Implementing",
  "size": "M",
  "priority": "High",
  "rank": 85,
  "blockedBy": ["PL164"],
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
- **Step 1, the window's contract, published up front and blocked by nothing:** `StoreException.NoForgeAccess`
  (address, right) in `store/api`, the code `NoForgeAccess` in `planner.yaml`, its answer in `Problems`, and
  `PlanningForgeAccess.handOnForgeToken` in the screen, an optional interface a host may implement, so the window
  builds against it at once and a host without it is offered nothing. **Step 2, the behavior**, after [PL164](README.md#pl164)'s types:
- `planner.yaml`: a new refusal code `NoForgeAccess` (HTTP 403) with fields `address` and, where GitHub names it,
  `right` (such as `Issues` or `Projects`). `ForgeAccessRefusals` (implements [PL164](README.md#pl164)'s `AccessRefusals`) raises it for no token in
  the vault and for a 401 or a 403; the right comes from the forge clients' `acceptedPermissions()` of the refusal.
- The screen: `PlanningHost` gains `Future<bool> handOnForgeToken(String address)`; the window puts the person's
  token into this computer's vault and answers whether it did. The screen offers it beside a `NoForgeAccess`
  refusal and reads the board again after `true`.
- Red first: a store test where a 403 naming `Projects` gives `NoForgeAccess` with that right; a controller test
  of the answer's code and fields; a widget test that shows the offer for `NoForgeAccess`, none for another
  refusal, and a refresh after `true`.

## Plan reading

Read by agy `gemini-4-argon-high` before the build; every answer is taken:

- `PlanningHost` is an `interface class`, which an implementer never inherits a default from, so the call stands on
  an optional interface of its own, `PlanningForgeAccess`, which the screen finds by `host is PlanningForgeAccess`
  as it finds `PlanningReconnect`; a host without it is offered nothing. The call names the provider whose planner
  refused, `handOnForgeToken({required String provider, required String address})`, so the token goes into that
  machine's vault, never the wrong one.
- The refusal travels in the store's existing 502 answers (`StoreRefused`, `SaveRefused`), named by its title, so no
  operation gains a new status; `address` and `right` join the Problem's parameters. Its words are fixed, the
  address is an `https` origin without user, query or fragment, and the right matches `[A-Za-z][A-Za-z0-9 _-]{0,63}`,
  checked where the refusal is made and again in the screen before it offers anything.
- Step 2: a task's save refused with `NoForgeAccess` is kept and replayed as that refusal (`WorkRun`'s save failure
  and `WorkRecord`), and a stage result refused with it is not marked refused but left to be handed in again, as
  `Busy` and `StaleRevision` are; a 401 or 403 after an earlier call of the same save already wrote is
  `PartlySaved`, never `NoForgeAccess`; a token that is missing when the project opens gives `NoForgeAccess` too;
  a refresh in the background that fails with it ends the project's stream, so the window sees the refusal and the
  offer.
- Step 2, the screen: the board keeps the refusal of a read, a move and an action as a `PlanningRefusal`, clears it
  when a read succeeds or the project changes, disables the offer while the call runs, and after `true` reads the
  project again through `reloadPlanning()` only if it is still the same board.

## Order and files

Built in this order, each pushed once what it needs is published: [PL164](README.md#pl164)'s types;
[PL169](README.md#pl169)'s contract (independent, at once); then [PL165](README.md#pl165), [PL125](README.md#pl125)
followed by [PL166](README.md#pl166), and [PL169](README.md#pl169)'s behavior followed by [PL167](README.md#pl167),
side by side; then [PL168](README.md#pl168). Each builds against the forge clients' published surface with fakes of
its own.

- Step 1 owns `store/api`'s `StoreException` (one new class), `planner.yaml`'s new response, `planner/app`'s
  `Problems` and the screen's `screen/lib/src/hooks.dart`. Step 2 owns `store/github`'s `ForgeAccessRefusals` and
  the screen's `screen/lib/src/forge_access_offer.dart` with its widget test.

## Acceptance

- In the test organization, a token without Projects rights gives the board's refusal naming `Projects`, and the
  offer stands beside it.
- The window's pin can be raised to the commit that carries both the refusal and the call.

## To be checked

- Both steps are built and on main, and the screen commit asked of the window's pin carries the refusal and the
  call. Still open: the board read in the test organization with a token without Projects rights, its refusal
  naming `Projects` and the offer beside it; no run has played it.
