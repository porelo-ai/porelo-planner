# ST180 — Prove one build works locally and centrally

**Priority:** 🔽 Low · **State:** Implementing

**What a user gains.** A person can rely on every building repository's workflow running unchanged on both the local and the central forge.

**The test.** Run every building repository's same workflow on the local and central forges without editing it. Resolve another repository's locally published artifact, including a child-module publication. Break that publication; the local run fails before any central tag. Request a declared machine kind, read the verdict and log in the task, and refuse undeclared kinds or commands.

**Issues:** none yet

## Notes

- Parent acceptance for one build, not duplicate implementation. This includes the forge, pool and task-build work from release 0.6 and this release; return the whole local VM after a run and keep provider names out of workflows.

## Data

```json
{
  "id": "ST180",
  "title": "Prove one build works locally and centrally",
  "state": "Implementing",
  "gain": "A person can rely on every building repository's workflow running unchanged on both the local and the central forge.",
  "test": "Run every building repository's same workflow on the local and central forges without editing it. Resolve another repository's locally published artifact, including a child-module publication. Break that publication; the local run fails before any central tag. Request a declared machine kind, read the verdict and log in the task, and refuse undeclared kinds or commands.",
  "priority": "Low",
  "issues": [],
  "tested": null
}
```
