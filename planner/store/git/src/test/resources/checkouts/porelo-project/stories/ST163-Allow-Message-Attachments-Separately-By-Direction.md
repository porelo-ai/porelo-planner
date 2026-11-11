# ST163 — Allow message attachments separately by direction

**Priority:** 🔽 Low · **State:** Analyzed

**What a user gains.** A person allows message attachments separately for each direction, and without permission they are refused or held.

**The test.** Send attachments both ways without permission; they are refused or held. Allow incoming attachments for one peer; its file reaches /porelo/files while outgoing attachments stay blocked. Try a URL on another host and an oversized attachment; both fail regardless of severity.

**Issues:**

- [porelo-project#PJ03](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj03) (Analyzed)

## Notes

- Configure Attachment Parts By Direction; Enforce Attachment Permissions On The Host; Carry Matrix Media References. Agree the extension to the message schema before implementation.

## Data

```json
{
  "id": "ST163",
  "title": "Allow message attachments separately by direction",
  "state": "Analyzed",
  "gain": "A person allows message attachments separately for each direction, and without permission they are refused or held.",
  "test": "Send attachments both ways without permission; they are refused or held. Allow incoming attachments for one peer; its file reaches /porelo/files while outgoing attachments stay blocked. Try a URL on another host and an oversized attachment; both fail regardless of severity.",
  "priority": "Low",
  "issues": [
    "porelo-project#PJ03"
  ],
  "tested": null
}
```
