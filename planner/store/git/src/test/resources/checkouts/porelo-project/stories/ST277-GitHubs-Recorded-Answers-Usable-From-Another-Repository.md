# ST277 — GitHub's recorded answers usable from another repository

**Priority:** ⏫ Highest · **State:** Finished (2026-11-02)

**What a user gains.** A developer in another repository can replay GitHub's recorded answers from the main and tests jar, without calling GitHub.

**The test.** A small program in another repository replays GitHub's answers from the main and the tests jar with no network, the tests jar read from Central's snapshots; the GitHub recorder keeps owning what an earlier request made when a later one of its kind is refused.

**Issues:**

- [porelo-forge-clients#FC12](https://github.com/porelo-ai/porelo-forge-clients/blob/main/issues/README.md#fc12) (Finished)
- [porelo-forge-clients#FC14](https://github.com/porelo-ai/porelo-forge-clients/blob/main/issues/README.md#fc14) (Finished)

## Data

```json
{
  "id": "ST277",
  "title": "GitHub's recorded answers usable from another repository",
  "state": "Finished",
  "gain": "A developer in another repository can replay GitHub's recorded answers from the main and tests jar, without calling GitHub.",
  "test": "A small program in another repository replays GitHub's answers from the main and the tests jar with no network, the tests jar read from Central's snapshots; the GitHub recorder keeps owning what an earlier request made when a later one of its kind is refused.",
  "priority": "Highest",
  "issues": [
    "porelo-forge-clients#FC12",
    "porelo-forge-clients#FC14"
  ],
  "tested": "2026-11-02"
}
```
