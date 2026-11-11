# ST77 — Direct pushes and red checks refused

**Priority:** 🟰 Medium · **State:** Accepted

**What a user gains.** Direct pushes to main and merges with red checks are refused for every account, so main only changes through checked pull requests.

**The test.** Try direct pushes to main with the project account and another account; both fail. Try to merge a pull request with a red required check; it is refused. A permitted person can merge once all required checks pass.

**Issues:**

- [porelo-project#PJ34](https://github.com/porelo-ai/porelo-project/blob/main/issues/README.md#pj34) (Accepted)

## Notes

- Require Checks On Pull Requests; Remove Direct Main Bypasses. Build these parts after the forge review path works.

## Data

```json
{
  "id": "ST77",
  "title": "Direct pushes and red checks refused",
  "state": "Accepted",
  "gain": "Direct pushes to main and merges with red checks are refused for every account, so main only changes through checked pull requests.",
  "test": "Try direct pushes to main with the project account and another account; both fail. Try to merge a pull request with a red required check; it is refused. A permitted person can merge once all required checks pass.",
  "priority": "Medium",
  "issues": [
    "porelo-project#PJ34"
  ],
  "tested": null
}
```
