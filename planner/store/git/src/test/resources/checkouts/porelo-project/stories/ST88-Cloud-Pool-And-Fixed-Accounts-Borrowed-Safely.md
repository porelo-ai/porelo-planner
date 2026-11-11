# ST88 — Cloud, pool and fixed accounts borrowed safely

**Priority:** 🟰 Medium · **State:** Idea

**What a user gains.** Machines from a cloud source, a pool or a fixed server are lent safely, so a second run cannot take a machine already in use.

**The test.** Request the same kind from a cloud source, pool and fixed server. A second run cannot take an occupied account. Return each lease; only its own cloud resources are deleted or its own borrowed account reset.

**Issues:** none yet

## Notes

- Provide Machines Through OpenTofu; Lend Pool Run Accounts; Lend Fixed Server Accounts. Continue the machine-source parent from sprint 6.

## Data

```json
{
  "id": "ST88",
  "title": "Cloud, pool and fixed accounts borrowed safely",
  "state": "Idea",
  "gain": "Machines from a cloud source, a pool or a fixed server are lent safely, so a second run cannot take a machine already in use.",
  "test": "Request the same kind from a cloud source, pool and fixed server. A second run cannot take an occupied account. Return each lease; only its own cloud resources are deleted or its own borrowed account reset.",
  "priority": "Medium",
  "issues": [],
  "tested": null
}
```
