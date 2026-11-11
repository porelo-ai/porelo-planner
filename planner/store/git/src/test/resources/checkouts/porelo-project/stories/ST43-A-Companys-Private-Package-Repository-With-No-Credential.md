# ST43 — A company's private package repository, with no credential in the task

**Priority:** 🔼 High · **State:** Analyzed

**What a user gains.** A task can build against a company's authenticated Nexus or Artifactory without ever holding the credential, since the settings are written outside the task.

**The test.** A task builds against an authenticated Nexus or Artifactory; `settings.xml` is written at the start and the credential stays in the vault.

**Issues:**

- [porelo#B222](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b222) (Analyzed)
- [porelo#B219](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b219) (Estimated)

## Data

```json
{
  "id": "ST43",
  "title": "A company's private package repository, with no credential in the task",
  "state": "Analyzed",
  "gain": "A task can build against a company's authenticated Nexus or Artifactory without ever holding the credential, since the settings are written outside the task.",
  "test": "A task builds against an authenticated Nexus or Artifactory; `settings.xml` is written at the start and the credential stays in the vault.",
  "priority": "High",
  "issues": [
    "porelo#B222",
    "porelo#B219"
  ],
  "tested": null
}
```
