# ST136 — Allow inner containers only in the proven case

**Priority:** 🔽 Low · **State:** Idea

**What a user gains.** Inner containers are allowed only in the proven case, so only allowed destinations work from inside one.

**The test.** Try allowed and unallowed destinations from an inner container; only the allowed one works. A guarded task with nesting enabled is refused. If measurements require a separate kernel, nesting on a shared kernel remains refused.

**Issues:** none yet

## Notes

- Enable Assessed Nested Containers; Verify Nested Container Refusals. If the assessment is negative, record the reasons and do not introduce nesting.

## Data

```json
{
  "id": "ST136",
  "title": "Allow inner containers only in the proven case",
  "state": "Idea",
  "gain": "Inner containers are allowed only in the proven case, so only allowed destinations work from inside one.",
  "test": "Try allowed and unallowed destinations from an inner container; only the allowed one works. A guarded task with nesting enabled is refused. If measurements require a separate kernel, nesting on a shared kernel remains refused.",
  "priority": "Low",
  "issues": [],
  "tested": null
}
```
