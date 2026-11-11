# ST112 — See the exact image and package contents

**Priority:** 🟰 Medium · **State:** Idea

**What a user gains.** A person can see the exact image and package contents, such as an SBOM, and an image tag alone is not enough for a project to follow.

**The test.** Read package contents or its SBOM. Follow a project with an image tag alone; start is refused until an explicit one-time exception. Change the digest; the next start names the change. Check a moving runner name; it is refused while a versioned runner is accepted.

**Issues:**

- [porelo#B41](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b41) (Estimated)
- [porelo#B98](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b98) (Estimated)
- [porelo-buildtools#BT18](https://github.com/porelo-ai/porelo-buildtools/blob/main/issues/README.md#bt18) (Idea)

## Notes

- Apply an image signature policy only within its explicitly chosen scope.

## Data

```json
{
  "id": "ST112",
  "title": "See the exact image and package contents",
  "state": "Idea",
  "gain": "A person can see the exact image and package contents, such as an SBOM, and an image tag alone is not enough for a project to follow.",
  "test": "Read package contents or its SBOM. Follow a project with an image tag alone; start is refused until an explicit one-time exception. Change the digest; the next start names the change. Check a moving runner name; it is refused while a versioned runner is accepted.",
  "priority": "Medium",
  "issues": [
    "porelo#B41",
    "porelo#B98",
    "porelo-buildtools#BT18"
  ],
  "tested": null
}
```
