# ST184 — Prove the Apple silicon VM can enforce the boundaries

**Priority:** 🔽 Low · **State:** Idea

**What a user gains.** A person on Apple silicon learns whether the local ARM VM can enforce the boundaries, with refusals showing what is missing.

**The test.** Probe Podman, nftables, user namespaces, cgroups and the daemon socket in ARM Lima. A refused unshare or missing firewall stops the task start. Check the ARM instruction baseline of the produced test executable.

**Issues:**

- [porelo#B251](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b251) (Idea)
- [porelo-project#PJ55](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj55) (Accepted)

## Notes

- Define And Check The ARM Baseline. The ARM parent also covers sprints 2 through 4. Use real Apple silicon; package building waits for this probe to pass.

## Data

```json
{
  "id": "ST184",
  "title": "Prove the Apple silicon VM can enforce the boundaries",
  "state": "Idea",
  "gain": "A person on Apple silicon learns whether the local ARM VM can enforce the boundaries, with refusals showing what is missing.",
  "test": "Probe Podman, nftables, user namespaces, cgroups and the daemon socket in ARM Lima. A refused unshare or missing firewall stops the task start. Check the ARM instruction baseline of the produced test executable.",
  "priority": "Low",
  "issues": [
    "porelo#B251",
    "porelo-project#PJ55"
  ],
  "tested": null
}
```
