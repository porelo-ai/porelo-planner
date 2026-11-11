# ST133 — Measure whether a task can have its own kernel

**Priority:** 🔽 Low · **State:** Estimated

**What a user gains.** A person can learn whether a task can have its own kernel, since the machine is probed with and without usable KVM.

**The test.** Probe with and without usable KVM; exercise network rules, sockets, attach and snapshot/restore. Without KVM or the runtime, doctor says unavailable. Without a working firewall, no task starts.

**Issues:**

- [porelo#B59](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b59) (Estimated)

## Notes

- Measure KVM-Backed Task Isolation; Report KVM Runtime Capability. The reach boundaries from release 0.11 must pass first. This parent also covers runtime adoption in sprint 9.

## Data

```json
{
  "id": "ST133",
  "title": "Measure whether a task can have its own kernel",
  "state": "Estimated",
  "gain": "A person can learn whether a task can have its own kernel, since the machine is probed with and without usable KVM.",
  "test": "Probe with and without usable KVM; exercise network rules, sockets, attach and snapshot/restore. Without KVM or the runtime, doctor says unavailable. Without a working firewall, no task starts.",
  "priority": "Low",
  "issues": [
    "porelo#B59"
  ],
  "tested": null
}
```
