# ST194 — Use a secrets file readable by a separate tool

**Priority:** 🔽 Low · **State:** Implementing

**What a user gains.** A person can open and change the secrets file in both KeePassXC and Porelo, with concurrent changes preserved or reported.

**The test.** Open and change the KDBX file in KeePassXC and Porelo. Concurrent changes are preserved or an explicit conflict stops the write; silent overwriting is refused. The drive contains no plaintext secrets.

**Issues:**

- [porelo-project#PJ107](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj107) (Backlog)

## Notes

- Read And Write KDBX Setup Secrets; Preserve Concurrent Secret File Changes. This parent also covers sprint 11; decide the file format and protection scope first.

## Data

```json
{
  "id": "ST194",
  "title": "Use a secrets file readable by a separate tool",
  "state": "Implementing",
  "gain": "A person can open and change the secrets file in both KeePassXC and Porelo, with concurrent changes preserved or reported.",
  "test": "Open and change the KDBX file in KeePassXC and Porelo. Concurrent changes are preserved or an explicit conflict stops the write; silent overwriting is refused. The drive contains no plaintext secrets.",
  "priority": "Low",
  "issues": [
      "porelo-project#PJ107"
  ],
  "tested": null
}
```
