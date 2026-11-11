# ST164 — Recognize a file spread across messages

**Priority:** 🔽 Low · **State:** Estimated

**What a user gains.** A file split over many messages, such as a ZIP or PEM, is recognized and refused once it passes the threshold.

**The test.** Split ZIP and PEM payloads into 2, 5, 20 and 100 messages and pass the threshold. The refusal names the contributing messages. Delete and rebuild the index; the verdict stays the same.

**Issues:**

- [porelo-message-sluice#SL08](https://github.com/porelo-ai/porelo-message-sluice/blob/main/issues/README.md#sl08) (Estimated)

## Notes

- Index Message Fragments; Reconstruct Accumulated Payloads. This parent also covers sprint 17.

## Data

```json
{
  "id": "ST164",
  "title": "Recognize a file spread across messages",
  "state": "Estimated",
  "gain": "A file split over many messages, such as a ZIP or PEM, is recognized and refused once it passes the threshold.",
  "test": "Split ZIP and PEM payloads into 2, 5, 20 and 100 messages and pass the threshold. The refusal names the contributing messages. Delete and rebuild the index; the verdict stays the same.",
  "priority": "Low",
  "issues": [
    "porelo-message-sluice#SL08"
  ],
  "tested": null
}
```
