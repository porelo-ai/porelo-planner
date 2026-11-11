# ST195 — Open the secrets file only on the desktop

**Priority:** 🔽 Low · **State:** Idea

**What a user gains.** A person opens the secrets file only on the desktop, and setup gets host secrets through standard input so they do not show on host disks or in arguments.

**The test.** Run setup with the secrets file and complete its stop. Inspect host disks and process arguments: the file is opened only on the desktop and the host never receives the whole decrypted collection. Enter the TOTP personally under the chosen rule.

**Issues:** none yet

## Notes

- Supply Host Stop Secrets Through Standard Input; Guide Setup Secret Storage Choices. Continue sprint 10.

## Data

```json
{
  "id": "ST195",
  "title": "Open the secrets file only on the desktop",
  "state": "Idea",
  "gain": "A person opens the secrets file only on the desktop, and setup gets host secrets through standard input so they do not show on host disks or in arguments.",
  "test": "Run setup with the secrets file and complete its stop. Inspect host disks and process arguments: the file is opened only on the desktop and the host never receives the whole decrypted collection. Enter the TOTP personally under the chosen rule.",
  "priority": "Low",
  "issues": [],
  "tested": null
}
```
