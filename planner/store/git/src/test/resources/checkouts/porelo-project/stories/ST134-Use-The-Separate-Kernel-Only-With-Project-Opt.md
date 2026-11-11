# ST134 — Use the separate kernel only with project opt-in

**Priority:** 🔽 Low · **State:** Idea

**What a user gains.** A task uses its separate kernel only when the project opts in, and a failed rule installation does not silently drop protection.

**The test.** Start projects with and without opt-in, then break rule installation. Only the opted-in project uses the new runtime; a start with ineffective rules is refused.

**Issues:** none yet

## Notes

- Adopt A KVM-Backed Runtime Opt-In. Proceed only after a positive decision from sprint 8 measurements.

## Data

```json
{
  "id": "ST134",
  "title": "Use the separate kernel only with project opt-in",
  "state": "Idea",
  "gain": "A task uses its separate kernel only when the project opts in, and a failed rule installation does not silently drop protection.",
  "test": "Start projects with and without opt-in, then break rule installation. Only the opted-in project uses the new runtime; a start with ineffective rules is refused.",
  "priority": "Low",
  "issues": [],
  "tested": null
}
```
