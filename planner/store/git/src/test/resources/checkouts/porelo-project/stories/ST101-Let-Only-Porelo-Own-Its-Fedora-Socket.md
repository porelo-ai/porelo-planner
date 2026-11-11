# ST101 — Let only Porelo own its Fedora socket

**Priority:** 🟰 Medium · **State:** Idea

**What a user gains.** On an enforcing SELinux Fedora machine, only Porelo can own its socket, so another program cannot stand in for it.

**The test.** Install the build on an enforcing SELinux Fedora machine and connect with Porelo. Have another program bind the protected socket; it is refused. Remove and reinstall the package; the module is removed and loaded as intended. Run the Fedora deploy path too, including --account.

**Issues:**

- [porelo#B135](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b135) (Analyzed)
- [porelo#B40](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b40) (Estimated)
- [porelo#B284](https://github.com/porelo-ai/porelo/blob/main/issues/base/README.md#b284) (Idea)

## Data

```json
{
  "id": "ST101",
  "title": "Let only Porelo own its Fedora socket",
  "state": "Idea",
  "gain": "On an enforcing SELinux Fedora machine, only Porelo can own its socket, so another program cannot stand in for it.",
  "test": "Install the build on an enforcing SELinux Fedora machine and connect with Porelo. Have another program bind the protected socket; it is refused. Remove and reinstall the package; the module is removed and loaded as intended. Run the Fedora deploy path too, including --account.",
  "priority": "Medium",
  "issues": [
    "porelo#B135",
    "porelo#B40",
    "porelo#B284"
  ],
  "tested": null
}
```
