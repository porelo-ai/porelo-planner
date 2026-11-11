# ST127 — Keep container limits on large hosts

**Priority:** 🔽 Low · **State:** Implementing

**What a user gains.** Container limits still apply on large hosts, so the same configuration behaves the same on small and large machines.

**The test.** Start the same configuration on a small and a large host. Read task CPU visibility and process limits. Run the homeserver and backup with a deliberately low process limit. Request an oversized VM pool; it is refused before creation.

**Issues:** none yet

## Notes

- Limit Tasks And Image Builds; Limit Homeserver And Backup Runs; Limit Forge And Runner Jobs; Check VM Pool Capacity.

## Data

```json
{
  "id": "ST127",
  "title": "Keep container limits on large hosts",
  "state": "Implementing",
  "gain": "Container limits still apply on large hosts, so the same configuration behaves the same on small and large machines.",
  "test": "Start the same configuration on a small and a large host. Read task CPU visibility and process limits. Run the homeserver and backup with a deliberately low process limit. Request an oversized VM pool; it is refused before creation.",
  "priority": "Low",
  "issues": [],
  "tested": null
}
```
