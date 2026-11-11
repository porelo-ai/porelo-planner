# ST162 — Give, receive and withdraw a file

**Priority:** 🔽 Low · **State:** Idea

**What a user gains.** A person can put a file in a task's drop and hand it to an allowed peer, and unsafe files such as symbolic links or oversized ones are refused.

**The test.** Put a file in the task's drop and hand it to an allowed peer. Try a symbolic link, an oversized file and an undeclared recipient; each handover fails. Read the recipient's permissions and withdraw the artifact; no shared writable task directory exists.

**Issues:** none yet

## Notes

- Export Artifacts From A Task; Receive And Withdraw Artifacts. Continue sprint 13.

## Data

```json
{
  "id": "ST162",
  "title": "Give, receive and withdraw a file",
  "state": "Idea",
  "gain": "A person can put a file in a task's drop and hand it to an allowed peer, and unsafe files such as symbolic links or oversized ones are refused.",
  "test": "Put a file in the task's drop and hand it to an allowed peer. Try a symbolic link, an oversized file and an undeclared recipient; each handover fails. Read the recipient's permissions and withdraw the artifact; no shared writable task directory exists.",
  "priority": "Low",
  "issues": [],
  "tested": null
}
```
