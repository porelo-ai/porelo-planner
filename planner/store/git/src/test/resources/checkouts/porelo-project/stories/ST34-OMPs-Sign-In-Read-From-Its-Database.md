# ST34 — OMP's sign-in read from its database

**Priority:** 🔼 High · **State:** Idea

**What a user gains.** A person can sign OMP in once and start tasks without running vault login, because the sign-in is read from OMP's own database.

**The test.** Sign OMP in and start a task with no `vault login`; the sign-in read from its SQLite database is offered for import.

**Issues:**

- [porelo-omp#OM33](https://github.com/porelo-ai/porelo-omp/blob/main/issues/README.md#om33) (Idea)

## Data

```json
{
  "id": "ST34",
  "title": "OMP's sign-in read from its database",
  "state": "Idea",
  "gain": "A person can sign OMP in once and start tasks without running vault login, because the sign-in is read from OMP's own database.",
  "test": "Sign OMP in and start a task with no `vault login`; the sign-in read from its SQLite database is offered for import.",
  "priority": "High",
  "issues": [
    "porelo-omp#OM33"
  ],
  "tested": null
}
```
