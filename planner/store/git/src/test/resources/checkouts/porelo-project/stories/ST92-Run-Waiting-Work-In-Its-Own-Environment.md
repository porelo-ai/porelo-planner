# ST92 — Run waiting work in its own environment

**Priority:** 🟰 Medium · **State:** Idea

**What a user gains.** A person can run waiting work in its own isolated environment with gate try and read the streamed result, without it reaching the gate.

**The test.** Run a waiting commit with gate try and read its streamed result. Try to reach the gate and vault from the trial; both are refused. Cancel the run and confirm its environment is removed.

**Issues:**

- [porelo#B95](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b95) (Idea)

## Notes

- Create An Isolated Review Trial; Stream Review Trial Results. The SARIF review path in release 0.8 is a prerequisite.

## Data

```json
{
  "id": "ST92",
  "title": "Run waiting work in its own environment",
  "state": "Idea",
  "gain": "A person can run waiting work in its own isolated environment with gate try and read the streamed result, without it reaching the gate.",
  "test": "Run a waiting commit with gate try and read its streamed result. Try to reach the gate and vault from the trial; both are refused. Cancel the run and confirm its environment is removed.",
  "priority": "Medium",
  "issues": [
    "porelo#B95"
  ],
  "tested": null
}
```
