# ST151 — Keep the homeserver and room entrances separate

**Priority:** 🔽 Low · **State:** Analyzed

**What a user gains.** The homeserver and room entrances are kept apart, so a second account cannot connect to another account's homeserver socket.

**The test.** Reach the homeserver through its account-owned Unix socket; a second account cannot connect. Read the room and a direct chat together and change the task reading them. Direct messages remain private, with no lost or repeated delivery.

**Issues:**

- [porelo-message-matrix#MX29](https://github.com/porelo-ai/porelo-message-matrix/blob/main/issues/README.md#mx29) (Backlog)
- [porelo-message-matrix#MX77](https://github.com/porelo-ai/porelo-message-matrix/blob/main/issues/README.md#mx77) (Analyzed)

## Notes

- Separate Room Polling And Positions. The room parent also covers sprints 9 through 11. Begin only after the move acceptance.

## Data

```json
{
  "id": "ST151",
  "title": "Keep the homeserver and room entrances separate",
  "state": "Analyzed",
  "gain": "The homeserver and room entrances are kept apart, so a second account cannot connect to another account's homeserver socket.",
  "test": "Reach the homeserver through its account-owned Unix socket; a second account cannot connect. Read the room and a direct chat together and change the task reading them. Direct messages remain private, with no lost or repeated delivery.",
  "priority": "Low",
  "issues": [
    "porelo-message-matrix#MX29",
    "porelo-message-matrix#MX77"
  ],
  "tested": null
}
```
