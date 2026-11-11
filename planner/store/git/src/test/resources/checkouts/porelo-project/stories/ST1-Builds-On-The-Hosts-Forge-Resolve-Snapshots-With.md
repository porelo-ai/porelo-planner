# ST1 — Builds on the host's forge resolve snapshots with a read-only token

**Priority:** ⏫ Highest · **State:** Review

**What a user gains.** Builds on the host's own forge can fetch Porelo snapshots with a read-only token, so they get what they need without being able to change anything.

**The test.** Every project's organization on the host holds `PORELO_PACKAGES_READ_TOKEN` (scope `read:package`) and its own `MAVEN_SNAPSHOTS_URL`; a build on an unreviewed branch resolves snapshots with it and holds no publisher's token, only the deploying step on `main` does; a missing `MAVEN_SNAPSHOTS_URL` fails the Build on any forge but github.com.

**Issues:**

- [porelo-buildtools#BT42](https://github.com/porelo-ai/porelo-buildtools/blob/main/issues/README.md#bt42) (Review)
- [porelo-host#HO52](https://github.com/porelo-ai/porelo-host/blob/main/issues/README.md#ho52) (Review)
- [porelo-buildtools#BT39](https://github.com/porelo-ai/porelo-buildtools/blob/main/issues/README.md#bt39) (Review)
- [porelo-message-sluice#SL36](https://github.com/porelo-ai/porelo-message-sluice/blob/main/issues/README.md#sl36) (Review)
- [porelo-claude-code#CC41](https://github.com/porelo-ai/porelo-claude-code/blob/main/issues/README.md#cc41) (Review)
- [porelo-pi#PI41](https://github.com/porelo-ai/porelo-pi/blob/main/issues/README.md#pi41) (Review)
- [porelo-omp#OM38](https://github.com/porelo-ai/porelo-omp/blob/main/issues/README.md#om38) (Review)

## Data

```json
{
  "id": "ST1",
  "title": "Builds on the host's forge resolve snapshots with a read-only token",
  "state": "Review",
  "gain": "Builds on the host's own forge can fetch Porelo snapshots with a read-only token, so they get what they need without being able to change anything.",
  "test": "Every project's organization on the host holds `PORELO_PACKAGES_READ_TOKEN` (scope `read:package`) and its own `MAVEN_SNAPSHOTS_URL`; a build on an unreviewed branch resolves snapshots with it and holds no publisher's token, only the deploying step on `main` does; a missing `MAVEN_SNAPSHOTS_URL` fails the Build on any forge but github.com.",
  "priority": "Highest",
  "issues": [
    "porelo-buildtools#BT42",
    "porelo-host#HO52",
    "porelo-buildtools#BT39",
    "porelo-message-sluice#SL36",
    "porelo-claude-code#CC41",
    "porelo-pi#PI41",
    "porelo-omp#OM38"
  ],
  "tested": null
}
```
