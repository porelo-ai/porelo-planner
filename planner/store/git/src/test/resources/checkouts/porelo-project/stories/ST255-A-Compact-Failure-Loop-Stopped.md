# ST255 — A compact failure loop stopped

**Priority:** ⏫ Highest · **State:** Finished (2026-10-28)

**What a user gains.** A task stops when an agent repeats a compact view redraw and a failed tool call in a loop, instead of running on without progress.

**The test.** Run an acceptance scenario with an agent that redraws a compact view and repeats a failed tool call; the loop watch stops it before the scenario bound and names the repeated failure. A progressing scenario must continue.

**Issues:**

- [porelo#B244](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b244) (Finished)

## Data

```json
{
  "id": "ST255",
  "title": "A compact failure loop stopped",
  "state": "Finished",
  "gain": "A task stops when an agent repeats a compact view redraw and a failed tool call in a loop, instead of running on without progress.",
  "test": "Run an acceptance scenario with an agent that redraws a compact view and repeats a failed tool call; the loop watch stops it before the scenario bound and names the repeated failure. A progressing scenario must continue.",
  "priority": "Highest",
  "issues": [
    "porelo#B244"
  ],
  "tested": "2026-10-28"
}
```
