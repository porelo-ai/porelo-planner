# ST275 — A published release built and verified

**Priority:** ⏫ Highest · **State:** Finished (2026-11-02)

**What a user gains.** A person can build a published release, such as Buildtools 0.5.1, and verify it, building IntelliJ from an empty Gradle home against the pinned version.

**The test.** Publish and pin Buildtools 0.5.1, then build IntelliJ from an empty Gradle home. Change a dependency digest; the build must fail.

**Issues:**

- [porelo-intellij#IJ09](https://github.com/porelo-ai/porelo-intellij/blob/main/issues/README.md#ij09) (Finished)

## Data

```json
{
  "id": "ST275",
  "title": "A published release built and verified",
  "state": "Finished",
  "gain": "A person can build a published release, such as Buildtools 0.5.1, and verify it, building IntelliJ from an empty Gradle home against the pinned version.",
  "test": "Publish and pin Buildtools 0.5.1, then build IntelliJ from an empty Gradle home. Change a dependency digest; the build must fail.",
  "priority": "Highest",
  "issues": [
    "porelo-intellij#IJ09"
  ],
  "tested": "2026-11-02"
}
```
