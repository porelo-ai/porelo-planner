# ST160 — Refuse oversized Git blobs at the gate

**Priority:** 🔽 Low · **State:** Estimated

**What a user gains.** Oversized Git blobs are refused at the gate, so a small blob waits for review and an oversized one never gets through.

**The test.** Configure a blob size limit and push a small and an oversized blob. The small blob waits for ordinary review; the large blob is refused with its size and the limit.

**Issues:**

- [porelo#B15](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b15) (Estimated)

## Notes

- Redesign Artifact Delivery Through Transports; Refuse Oversized Gate Blobs. This parent also covers sprints 13 and 14.

## Data

```json
{
  "id": "ST160",
  "title": "Refuse oversized Git blobs at the gate",
  "state": "Estimated",
  "gain": "Oversized Git blobs are refused at the gate, so a small blob waits for review and an oversized one never gets through.",
  "test": "Configure a blob size limit and push a small and an oversized blob. The small blob waits for ordinary review; the large blob is refused with its size and the limit.",
  "priority": "Low",
  "issues": [
    "porelo#B15"
  ],
  "tested": null
}
```
