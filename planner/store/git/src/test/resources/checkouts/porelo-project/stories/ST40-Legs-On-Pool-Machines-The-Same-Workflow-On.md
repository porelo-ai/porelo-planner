# ST40 — Legs on pool machines, the same workflow on both forges

**Priority:** 🔼 High · **State:** Idea

**What a user gains.** The same acceptance legs run on pool machines on both the host's forge and the central one, so tests behave the same wherever they run.

**The test.** On the host's Forgejo, porelo's and each agent adapter's acceptance legs take a pool account by kind and the build is green; on GitHub the same workflow rents a server. No workflow names a provider.

**Issues:**

- [porelo-host#HO27](https://github.com/porelo-ai/porelo-host/blob/main/issues/README.md#ho27) (Idea)
- [porelo#B322](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b322) (Idea)
- [porelo-claude-code#CC40](https://github.com/porelo-ai/porelo-claude-code/blob/main/issues/README.md#cc40) (Idea)
- [porelo-pi#PI40](https://github.com/porelo-ai/porelo-pi/blob/main/issues/README.md#pi40) (Idea)
- [porelo-omp#OM37](https://github.com/porelo-ai/porelo-omp/blob/main/issues/README.md#om37) (Idea)
- [porelo-agy#AG05](https://github.com/porelo-ai/porelo-agy/blob/main/issues/README.md#ag05) (Idea)

## Data

```json
{
  "id": "ST40",
  "title": "Legs on pool machines, the same workflow on both forges",
  "state": "Idea",
  "gain": "The same acceptance legs run on pool machines on both the host's forge and the central one, so tests behave the same wherever they run.",
  "test": "On the host's Forgejo, porelo's and each agent adapter's acceptance legs take a pool account by kind and the build is green; on GitHub the same workflow rents a server. No workflow names a provider.",
  "priority": "High",
  "issues": [
    "porelo-host#HO27",
    "porelo#B322",
    "porelo-claude-code#CC40",
    "porelo-pi#PI40",
    "porelo-omp#OM37",
    "porelo-agy#AG05"
  ],
  "tested": null
}
```
