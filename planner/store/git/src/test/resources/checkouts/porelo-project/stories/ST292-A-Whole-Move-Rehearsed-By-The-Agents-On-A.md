# ST292 — A whole move rehearsed by the agents on a rented server

**Priority:** ⏫ Highest · **State:** Backlog

**What a user gains.** Once a person has rented a server, the agents rehearse a whole move on it by themselves with a test identity in a test organization: the host, the window driven in a local VM, the room and the push and pull to central; problems show before the person's own test, and the person only rents and deletes the server.

**The test.** The person rents a server with the rehearsal's root key and gives the order; the run sets up the host, signs in the test person, drives the window's main scenarios and the room's scenarios, pushes through the gate and pushes and pulls to central, takes Porelo off and asks to delete the server, reporting OK or FAIL per step, with no input from the person between renting and deleting.

**Issues:**

- [porelo#B553](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b553) (Implementing)
- [porelo#B554](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b554) (Implementing)
- [porelo-frontend#F444](https://github.com/porelo-ai/porelo-frontend/blob/main/issues/README.md#f444) (Implementing)
- [porelo-frontend#F445](https://github.com/porelo-ai/porelo-frontend/blob/main/issues/README.md#f445) (Implementing)
- [porelo-host#HO158](https://github.com/porelo-ai/porelo-host/blob/main/issues/README.md#ho158) (Implementing)
- [porelo-message-matrix#MX89](https://github.com/porelo-ai/porelo-message-matrix/blob/main/issues/README.md#mx89) (Implementing)
- [porelo-message-matrix#MX90](https://github.com/porelo-ai/porelo-message-matrix/blob/main/issues/README.md#mx90) (Implementing)
- [porelo-message-matrix#MX91](https://github.com/porelo-ai/porelo-message-matrix/blob/main/issues/README.md#mx91) (Implementing)

## Data

```json
{
  "id": "ST292",
  "title": "A whole move rehearsed by the agents on a rented server",
  "state": "Backlog",
  "gain": "Once a person has rented a server, the agents rehearse a whole move on it by themselves with a test identity in a test organization: the host, the window driven in a local VM, the room and the push and pull to central; problems show before the person's own test, and the person only rents and deletes the server.",
  "test": "The person rents a server with the rehearsal's root key and gives the order; the run sets up the host, signs in the test person, drives the window's main scenarios and the room's scenarios, pushes through the gate and pushes and pulls to central, takes Porelo off and asks to delete the server, reporting OK or FAIL per step, with no input from the person between renting and deleting.",
  "priority": "Highest",
  "issues": [
    "porelo#B553",
    "porelo#B554",
    "porelo-frontend#F444",
    "porelo-frontend#F445",
    "porelo-host#HO158",
    "porelo-message-matrix#MX89",
    "porelo-message-matrix#MX90",
    "porelo-message-matrix#MX91"
  ],
  "tested": null
}
```
