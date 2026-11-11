# ST86 — Rented and given back through the module, a state from another computer refused

**Priority:** 🟰 Medium · **State:** Accepted

**What a user gains.** A person can rent a Scaleway host through the module and give it back, and a state file from another computer is refused.

**The test.** `porelo-host up` rents a Scaleway host through the module and `down` gives it back; afterwards `tofu plan` reads no change, and nothing tagged with the project is left at the provider. A tagged server the module's state lacks makes `up` refuse, naming it; a conflict copy of the state makes `up` and `down` refuse until a person keeps one.

**Issues:**

- [porelo-host#HO23](https://github.com/porelo-ai/porelo-host/blob/main/issues/README.md#ho23) (Estimated)
- [porelo-frontend#F166](https://github.com/porelo-ai/porelo-frontend/blob/main/issues/README.md#f166) (Accepted)

## Data

```json
{
  "id": "ST86",
  "title": "Rented and given back through the module, a state from another computer refused",
  "state": "Accepted",
  "gain": "A person can rent a Scaleway host through the module and give it back, and a state file from another computer is refused.",
  "test": "`porelo-host up` rents a Scaleway host through the module and `down` gives it back; afterwards `tofu plan` reads no change, and nothing tagged with the project is left at the provider. A tagged server the module's state lacks makes `up` refuse, naming it; a conflict copy of the state makes `up` and `down` refuse until a person keeps one.",
  "priority": "Medium",
  "issues": [
    "porelo-host#HO23",
    "porelo-frontend#F166"
  ],
  "tested": null
}
```
