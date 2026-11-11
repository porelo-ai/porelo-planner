# ST200 — Close WSL and SSH sessions without leftovers

**Priority:** 🔽 Low · **State:** Idea

**What a user gains.** A person using IntelliJ on Windows can cancel WSL or SSH connections or close the IDE without leaving sessions behind.

**The test.** Use WSL and SSH from IntelliJ on real Windows, cancel while connecting and close the IDE. No child process, socket file or partial probe response remains. Reproduce any suspected relay defect before fixing it.

**Issues:**

- [porelo-intellij#IJ10](https://github.com/porelo-ai/porelo-intellij/blob/main/issues/README.md#ij10) (Idea)
- [porelo-intellij#IJ11](https://github.com/porelo-ai/porelo-intellij/blob/main/issues/README.md#ij11) (Idea)

## Data

```json
{
  "id": "ST200",
  "title": "Close WSL and SSH sessions without leftovers",
  "state": "Idea",
  "gain": "A person using IntelliJ on Windows can cancel WSL or SSH connections or close the IDE without leaving sessions behind.",
  "test": "Use WSL and SSH from IntelliJ on real Windows, cancel while connecting and close the IDE. No child process, socket file or partial probe response remains. Reproduce any suspected relay defect before fixing it.",
  "priority": "Low",
  "issues": [
    "porelo-intellij#IJ10",
    "porelo-intellij#IJ11"
  ],
  "tested": null
}
```
