# PL113 — The Window Knows Its Planner Before It Sends The Token

```json
{
  "$schema": "issue-1.json",
  "id": "PL113",
  "title": "The Window Knows Its Planner Before It Sends The Token",
  "state": "Backlog",
  "size": "M",
  "priority": "Medium",
  "rank": 49,
  "blockedBy": [],
  "covers": "Before the window sends the planner's token or names a project, the listener at the address file's port proves it holds the token without receiving it, so a process that took the port after the planner ended learns neither"
}
```

**Size:** M - one new operation in the contract and the app with its tests, on Linux and Windows alike; the window's
own check is a change in the window's repository, agreed in the channel first.

**Estimate:** 67 min, the measured median of M.

**What must be true.** The window gives the planner's token and a project's name only to the account's own planner.
Today the screen reads the address and the token from the planner's address file, checked to be the account's own,
and sends the token as a bearer with its first call. A planner that ended leaves its address file behind, and another
account's process that binds the freed port on the loopback address receives the token and the project's name with
the next call.

## Plan

- **Today, read in the code.** Every operation in `planner.yaml` needs the bearer token; a missing or wrong one answers
  401 with an empty body. The screen's `HttpPlanningSource` sends the token with every call, beginning with the first.
- **A proof that sends no secret.** A new operation answers without a token: the caller sends a fresh random
  challenge, and the planner answers a keyed hash of it under its token, with a label that binds the answer to this
  use alone. The screen checks the answer before any other call, in constant time, and refuses the address as not the
  account's planner when it does not match. A wrong or missing answer is never retried with the token.
- **Bounds.** The operation takes a challenge of a fixed length only, answers nothing else, and logs no challenge or
  answer.
- **The window.** It uses the screen's source, so the check reaches it without a change of its own beyond the
  screen's pin; anything the window calls on the planner without the screen is named in the agreement.
- **Tests, red first.**
  - Unit, app: the operation answers the keyed hash for the planner's token, refuses a challenge of another length,
    and answers the same for a wrong bearer as for none.
  - Unit, screen, over a fake listener: a listener that cannot answer the challenge receives no token and no project
    name. Harm: the planner's token and a project's name given to another account's process.

## Acceptance

- A process on the planner's old port that does not hold the token learns neither the token nor a project's name from
  the window; the account's planner is reached as today.
- Each new guard is seen red by a break that compiles.

## To be checked

- Which calls the window makes on the planner without the screen's source, read in the window's repository through
  its agent.
