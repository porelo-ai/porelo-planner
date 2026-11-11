# ST103 — Keep image builds within their allowed sources

**Priority:** 🟰 Medium · **State:** Accepted

**What a user gains.** Image builds fetch only from allowed sources, working with public and authenticated private ones, and an unlisted source is refused.

**The test.** Build using an allowed public source and an authenticated private source. Fetch an unallowed destination from the snippet; that fetch is refused while the allowed fetches work. Inspect layers, build context and arguments; the private credential is absent.

**Issues:**

- [porelo#B207](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b207) (Rejected)
- [porelo#B223](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b223) (Accepted)

## Notes

- Private package sources from release 0.7 must work first.

## Data

```json
{
  "id": "ST103",
  "title": "Keep image builds within their allowed sources",
  "state": "Accepted",
  "gain": "Image builds fetch only from allowed sources, working with public and authenticated private ones, and an unlisted source is refused.",
  "test": "Build using an allowed public source and an authenticated private source. Fetch an unallowed destination from the snippet; that fetch is refused while the allowed fetches work. Inspect layers, build context and arguments; the private credential is absent.",
  "priority": "Medium",
  "issues": [
    "porelo#B207",
    "porelo#B223"
  ],
  "tested": null
}
```
