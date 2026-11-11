# ST122 — Unlock the machine with the right touched token

**Priority:** 🔽 Low · **State:** Idea

**What a user gains.** A person can unlock a machine after a restart with the right touched token, and the wrong or missing token is refused.

**The test.** Enroll a machine with the token and unlock after a restart. Try without the token and without touching it; both fail within a bounded wait with a clear reason. Only nonsecret derivation parameters remain stored.

**Issues:** none yet

## Notes

- Enroll A Machine With FIDO2; Record Verifiable Slot Evidence. Continue the hardware-protection parents from sprint 5.

## Data

```json
{
  "id": "ST122",
  "title": "Unlock the machine with the right touched token",
  "state": "Idea",
  "gain": "A person can unlock a machine after a restart with the right touched token, and the wrong or missing token is refused.",
  "test": "Enroll a machine with the token and unlock after a restart. Try without the token and without touching it; both fail within a bounded wait with a clear reason. Only nonsecret derivation parameters remain stored.",
  "priority": "Low",
  "issues": [],
  "tested": null
}
```
