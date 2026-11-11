# ST161 — Carry signed artifacts through the transport

**Priority:** 🔽 Low · **State:** Idea

**What a user gains.** Signed artifacts can be put, fetched, listed and removed through the transport, and a transport without artifact support is refused.

**The test.** Use Put, Fetch, List and Remove with the stub. Offer a transport without artifact support and an artifact with a wrong signature; both are refused. The host uses the shared contract for every transport.

**Issues:** none yet

## Notes

- Add Artifact Transport Operations; Provide Artifact Contract Scenarios. Continue sprint 12.

## Data

```json
{
  "id": "ST161",
  "title": "Carry signed artifacts through the transport",
  "state": "Idea",
  "gain": "Signed artifacts can be put, fetched, listed and removed through the transport, and a transport without artifact support is refused.",
  "test": "Use Put, Fetch, List and Remove with the stub. Offer a transport without artifact support and an artifact with a wrong signature; both are refused. The host uses the shared contract for every transport.",
  "priority": "Low",
  "issues": [],
  "tested": null
}
```
