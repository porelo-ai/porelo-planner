# ST16 — The first release's leg installs the release

**Priority:** ⏫ Highest · **State:** Backlog

**What a user gains.** The first release can be installed from its published packages in a test leg, so a release is proven installable before people use it.

**The test.** Not a walk: on the first tag run of 0.6, on a `porelo-buildtools` release holding the change, an agent repository's and `porelo-frontend`'s legs install the released Porelo from its channel, and their logs name it; a reader refuses a wrong channel or a candidate as proof of the tested release. The release stops if it fails.

**Issues:**

- [porelo-buildtools#BT01](https://github.com/porelo-ai/porelo-buildtools/blob/main/issues/README.md#bt01) (Backlog)

## Data

```json
{
  "id": "ST16",
  "title": "The first release's leg installs the release",
  "state": "Backlog",
  "gain": "The first release can be installed from its published packages in a test leg, so a release is proven installable before people use it.",
  "test": "Not a walk: on the first tag run of 0.6, on a `porelo-buildtools` release holding the change, an agent repository's and `porelo-frontend`'s legs install the released Porelo from its channel, and their logs name it; a reader refuses a wrong channel or a candidate as proof of the tested release. The release stops if it fails.",
  "priority": "Highest",
  "issues": [
    "porelo-buildtools#BT01"
  ],
  "tested": null
}
```
