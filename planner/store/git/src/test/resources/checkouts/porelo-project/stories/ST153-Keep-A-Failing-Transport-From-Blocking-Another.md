# ST153 — Keep a failing transport from blocking another

**Priority:** 🔽 Low · **State:** Idea

**What a user gains.** A broken transport cannot block a working one, so the host keeps talking through the transports that work.

**The test.** Connect the host to a working stub and a deliberately broken one. Call the broken transport; its error is named and the working transport still delivers. Inspect processes and task environments; secrets are absent.

**Issues:** none yet

## Notes

- Use The Transport Client On The Host; Protect Transport Calls And Secrets. Continue sprint 4 after the transport secret-input contract from release 0.13 works.

## Data

```json
{
  "id": "ST153",
  "title": "Keep a failing transport from blocking another",
  "state": "Idea",
  "gain": "A broken transport cannot block a working one, so the host keeps talking through the transports that work.",
  "test": "Connect the host to a working stub and a deliberately broken one. Call the broken transport; its error is named and the working transport still delivers. Inspect processes and task environments; secrets are absent.",
  "priority": "Low",
  "issues": [],
  "tested": null
}
```
