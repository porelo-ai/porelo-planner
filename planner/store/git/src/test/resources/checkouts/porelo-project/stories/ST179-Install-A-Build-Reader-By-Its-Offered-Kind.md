# ST179 — Install a build reader by its offered kind

**Priority:** 🔽 Low · **State:** Idea

**What a user gains.** A person finds build reader packages offered by kind in the setup list and the daemon's installable catalog, and can install one.

**The test.** Read the setup --list and daemon Installable catalogs; reader packages appear as kind build-reader. Select and install one through setup --with and query a build through it. A package outside the declared catalog is refused.

**Issues:**

- [porelo#B287](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b287) (Idea)

## Data

```json
{
  "id": "ST179",
  "title": "Install a build reader by its offered kind",
  "state": "Idea",
  "gain": "A person finds build reader packages offered by kind in the setup list and the daemon's installable catalog, and can install one.",
  "test": "Read the setup --list and daemon Installable catalogs; reader packages appear as kind build-reader. Select and install one through setup --with and query a build through it. A package outside the declared catalog is refused.",
  "priority": "Low",
  "issues": [
    "porelo#B287"
  ],
  "tested": null
}
```
